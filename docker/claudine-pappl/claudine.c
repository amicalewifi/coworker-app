/*
 * claudine — printer-application PAPPL pour l'imprimante virtuelle Claudine
 * de l'Amicale du WiFi.
 *
 * Architecture :
 *   1. PAPPL gère toute la couche IPP Everywhere (HTTP, parsing IPP, spool,
 *      conversion PDF/PWG-Raster, mDNS/DNS-SD, capabilities IPP standard).
 *   2. driver_cb() déclare les capabilities du driver (résolutions, médias,
 *      duplex, color, MIME types) — c'est ce qui rend la queue conforme IPP
 *      Everywhere et fait que Windows/macOS/iOS l'acceptent sans hack.
 *   3. auth_cb() accepte n'importe quel user/password au niveau IPP et stash
 *      le password dans une map globale (clé = username) pour que process_cb
 *      puisse le retrouver et le passer au backend Python.
 *   4. process_cb() est invoqué par PAPPL pour chaque job. Il récupère le
 *      PDF spoulé, construit argv/env compatibles avec amicale-broker, popen
 *      le script Python, attend, translate exit code → status PAPPL.
 *
 * Le contrat avec amicale-broker.py et avec coworker-app
 * (/api/v1/print/{submit,jobId/complete,jobId/error}) est strictement
 * préservé — c'est le même backend Python qu'avant, juste invoqué par PAPPL
 * au lieu de cupsd.
 *
 * Build : voir Makefile (utilise libpappl, libcups, libavahi-client).
 * Run   : /usr/local/bin/claudine server (foreground, géré par entrypoint.sh)
 */

#include <pappl/pappl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* ─── Constantes Claudine ──────────────────────────────────────────────── */

#define CLAUDINE_DRIVER_NAME       "claudine"
#define CLAUDINE_PRINTER_NAME      "Claudine"
#define CLAUDINE_DEVICE_URI        "file:///dev/null"   /* device factice ; on ne passe pas par PAPPL pour push à la Kyocera, on délègue à amicale-broker */
#define CLAUDINE_BROKER_PATH       "/usr/local/bin/amicale-broker"
#define CLAUDINE_LISTEN_PORT       8000
#define CLAUDINE_SPOOL_DIR         "/var/lib/pappl"
#define CLAUDINE_AUTH_SERVICE      ""                   /* vide = on n'utilise pas PAM, on a notre callback */

/* Codes de sortie hérités CUPS retournés par amicale-broker.py. */
#define BROKER_OK                  0
#define BROKER_FAILED              1
#define BROKER_AUTH_REQUIRED       2
#define BROKER_HOLD                3
#define BROKER_STOP                4
#define BROKER_CANCEL              5
#define BROKER_RETRY               6

/* ─── Map username → password (auth bridge) ────────────────────────────── */
/*
 * Bridge entre l'auth callback (HTTP request) et le process callback (job
 * processing async). PAPPL ne propose pas de chemin direct pour passer le
 * password depuis HTTP basic-auth jusqu'au job, donc on maintient une petite
 * table en mémoire (clé = username, valeur = password + timestamp).
 *
 * Concurrence : protégée par mutex. Capacité fixe (16 entrées suffisent vu
 * notre échelle — quelques users simultanés max). LRU-evict sur insertion
 * pleine. Eviction aussi des entrées > 10 min (couvre largement le délai
 * entre Basic auth et fin de spool d'un PDF de 500MB).
 */
#define CRED_CAP        16
#define CRED_TTL_SEC    600

typedef struct {
    char    username[128];
    char    password[256];
    time_t  ts;
} cred_entry_t;

static cred_entry_t   g_creds[CRED_CAP];
static pthread_mutex_t g_creds_mu = PTHREAD_MUTEX_INITIALIZER;

static void cred_put(const char *user, const char *pass)
{
    if (!user || !*user || !pass) return;
    time_t now = time(NULL);
    int slot = -1, oldest = -1;
    time_t oldest_ts = now + 1;

    pthread_mutex_lock(&g_creds_mu);
    for (int i = 0; i < CRED_CAP; i++) {
        /* Match existant (refresh) */
        if (strncmp(g_creds[i].username, user, sizeof(g_creds[i].username)) == 0) {
            slot = i;
            break;
        }
        /* Slot vide */
        if (slot < 0 && g_creds[i].username[0] == '\0') slot = i;
        /* Plus ancien (pour LRU) */
        if (g_creds[i].ts < oldest_ts) {
            oldest_ts = g_creds[i].ts;
            oldest = i;
        }
    }
    if (slot < 0) slot = oldest;        /* tableau plein → écrase le plus ancien */
    snprintf(g_creds[slot].username, sizeof(g_creds[slot].username), "%s", user);
    snprintf(g_creds[slot].password, sizeof(g_creds[slot].password), "%s", pass);
    g_creds[slot].ts = now;
    pthread_mutex_unlock(&g_creds_mu);
}

/* Retourne le password (copie dans `out`, taille `out_sz`) ; "" si introuvable. */
static void cred_get(const char *user, char *out, size_t out_sz)
{
    out[0] = '\0';
    if (!user || !*user) return;
    time_t now = time(NULL);

    pthread_mutex_lock(&g_creds_mu);
    for (int i = 0; i < CRED_CAP; i++) {
        if (g_creds[i].username[0] != '\0' &&
            strncmp(g_creds[i].username, user, sizeof(g_creds[i].username)) == 0 &&
            (now - g_creds[i].ts) <= CRED_TTL_SEC) {
            snprintf(out, out_sz, "%s", g_creds[i].password);
            break;
        }
    }
    pthread_mutex_unlock(&g_creds_mu);
}

/* ─── Auth callback ────────────────────────────────────────────────────── */
/*
 * Appelé par PAPPL pour chaque requête HTTP qui tombe sur une route protégée.
 * Notre stratégie : accepter tout au niveau IPP (la vraie auth est faite par
 * amicale-broker → coworker-app via le print_token), mais profiter du passage
 * pour stash le password dans la map globale.
 *
 * Note : la signature exacte du callback peut varier entre versions PAPPL.
 * Si compilation échoue, vérifier `<pappl/system.h>` et adapter les
 * paramètres (PAPPL ≥ 1.4 expose papplSystemSetAuthCallback).
 */
static http_status_t claudine_auth_cb(pappl_client_t *client,
                                       const char *group,
                                       void *data)
{
    (void)group;
    (void)data;

    const char *user = papplClientGetUsername(client);
    const char *pass = papplClientGetPassword(client);

    if (user && pass && *user && *pass) {
        cred_put(user, pass);
    }
    /* Toujours accepter — la validation finale est dans process_cb via
     * amicale-broker → coworker-app /api/v1/print/submit. */
    return HTTP_STATUS_CONTINUE;
}

/* ─── Driver capabilities ──────────────────────────────────────────────── */
/*
 * Déclare les capabilities IPP Everywhere de Claudine. C'est ce que Windows
 * IPP Class Driver lit dans Get-Printer-Attributes pour décider d'auto-
 * configurer la queue sans demander de driver à l'utilisateur.
 *
 * Valeurs alignées avec ce que la Kyocera TASKalfa 352ci sait faire :
 *   - A4 + Letter (medias par défaut au coworking)
 *   - 300 et 600 dpi (résolutions usuelles bureau)
 *   - color + monochrome
 *   - duplex one-sided / two-sided long edge / two-sided short edge
 */
static bool claudine_driver_cb(pappl_system_t *system,
                                const char *driver_name,
                                const char *device_uri,
                                const char *device_id,
                                pappl_pr_driver_data_t *driver_data,
                                ipp_t **driver_attrs,
                                void *data)
{
    (void)system;
    (void)device_uri;
    (void)device_id;
    (void)driver_attrs;
    (void)data;

    if (!driver_name || strcmp(driver_name, CLAUDINE_DRIVER_NAME) != 0) {
        return false;
    }

    /* Identification de l'imprimante */
    snprintf(driver_data->make_and_model, sizeof(driver_data->make_and_model),
             "Amicale du WiFi Claudine");
    driver_data->ppm        = 22;        /* pages/min couleur */
    driver_data->ppm_color  = 22;
    driver_data->kind       = PAPPL_KIND_OFFICE | PAPPL_KIND_DOCUMENT;

    /* IEEE 1284 device ID — fait croire à Windows que c'est une IPP
     * Everywhere standard, et l'empêche de tenter l'op propriétaire 0x4000. */
    snprintf(driver_data->device_id, sizeof(driver_data->device_id),
             "MFG:Amicale du WiFi;MDL:Claudine;CMD:PWGRaster,PDF;"
             "CLS:PRINTER;DRV:DM_IPP,R0,M0,LM_IPP_Everywhere;");

    /* Formats acceptés (PAPPL convertit automatiquement) */
    driver_data->format     = "application/pdf";
    driver_data->num_type   = 4;
    driver_data->type[0]    = "application/pdf";
    driver_data->type[1]    = "image/pwg-raster";
    driver_data->type[2]    = "image/urf";              /* AppleRaster, AirPrint */
    driver_data->type[3]    = "image/jpeg";

    /* Résolutions */
    driver_data->num_resolution = 2;
    driver_data->x_resolution[0] = 300; driver_data->y_resolution[0] = 300;
    driver_data->x_resolution[1] = 600; driver_data->y_resolution[1] = 600;
    driver_data->x_default = driver_data->y_default = 300;

    /* Color modes */
    driver_data->color_supported = PAPPL_COLOR_MODE_AUTO
                                 | PAPPL_COLOR_MODE_COLOR
                                 | PAPPL_COLOR_MODE_MONOCHROME;
    driver_data->color_default   = PAPPL_COLOR_MODE_AUTO;

    /* Duplex */
    driver_data->sides_supported = PAPPL_SIDES_ONE_SIDED
                                 | PAPPL_SIDES_TWO_SIDED_LONG_EDGE
                                 | PAPPL_SIDES_TWO_SIDED_SHORT_EDGE;
    driver_data->sides_default   = PAPPL_SIDES_ONE_SIDED;

    /* Quality */
    driver_data->quality_default = IPP_QUALITY_NORMAL;

    /* Médias : A4 + Letter, alimentation auto */
    driver_data->num_media = 2;
    driver_data->media[0]  = "iso_a4_210x297mm";
    driver_data->media[1]  = "na_letter_8.5x11in";

    driver_data->num_source = 1;
    driver_data->source[0]  = "auto";

    driver_data->num_type_media = 1;
    driver_data->type_media[0]  = "stationery";

    /* Médias chargés (= ready) — A4 par défaut */
    for (int i = 0; i < 1; i++) {
        snprintf(driver_data->media_ready[i].size_name,
                 sizeof(driver_data->media_ready[i].size_name),
                 "iso_a4_210x297mm");
        driver_data->media_ready[i].size_width  = 21000;   /* 1/100 mm */
        driver_data->media_ready[i].size_length = 29700;
        driver_data->media_ready[i].bottom_margin = 423;   /* ~3 mm */
        driver_data->media_ready[i].top_margin    = 423;
        driver_data->media_ready[i].left_margin   = 423;
        driver_data->media_ready[i].right_margin  = 423;
        snprintf(driver_data->media_ready[i].source,
                 sizeof(driver_data->media_ready[i].source), "auto");
        snprintf(driver_data->media_ready[i].type,
                 sizeof(driver_data->media_ready[i].type), "stationery");
    }
    memcpy(&driver_data->media_default, &driver_data->media_ready[0],
           sizeof(driver_data->media_default));

    /* Output bin */
    driver_data->num_bin = 1;
    driver_data->bin[0]  = "face-down";
    driver_data->bin_default = 0;

    /* Pas de finition / staple / etc. */
    driver_data->finishings = PAPPL_FINISHINGS_NONE;

    /* Wire protocol côté process job : nous on délègue à amicale-broker via
     * popen, donc PAPPL n'a pas à dialoguer avec un device physique. */
    driver_data->printfile_cb  = NULL;
    driver_data->rendjob_cb    = NULL;
    driver_data->rendpage_cb   = NULL;
    driver_data->rstartjob_cb  = NULL;
    driver_data->rstartpage_cb = NULL;
    driver_data->rwriteline_cb = NULL;

    return true;
}

/* ─── Auto-add callback ────────────────────────────────────────────────── */
/* Quand PAPPL découvre un device, retourne le nom du driver à utiliser.
 * On a un seul driver hardcodé. */
static const char *claudine_autoadd_cb(const char *device_info,
                                        const char *device_uri,
                                        const char *device_id,
                                        void *data)
{
    (void)device_info;
    (void)device_uri;
    (void)device_id;
    (void)data;
    return CLAUDINE_DRIVER_NAME;
}

/* ─── Process job callback ─────────────────────────────────────────────── */
/*
 * Appelé par PAPPL pour chaque job IPP. PAPPL a déjà spoulé le job en PDF
 * (conversion automatique depuis PWG-Raster, JPEG, etc.). On délègue tout à
 * amicale-broker.py via popen, en lui passant exactement les mêmes argv/env
 * qu'autrefois cupsd lui passait → préserve le contrat existant.
 */
static bool claudine_process_cb(pappl_job_t *job, pappl_device_t *device)
{
    (void)device;

    const char *job_id_str  = papplJobGetID_String(job);  /* fallback ; voir note */
    int         job_id      = papplJobGetID(job);
    const char *user        = papplJobGetUsername(job);
    const char *job_name    = papplJobGetName(job);
    int         copies      = papplJobGetCopies(job);
    const char *spool_path  = papplJobGetFilename(job);

    /* Sécurise les nullables. */
    if (!user)       user      = "";
    if (!job_name)   job_name  = "untitled";
    if (!spool_path) spool_path = "";
    if (copies < 1)  copies    = 1;

    char job_id_buf[32];
    snprintf(job_id_buf, sizeof(job_id_buf), "%d", job_id);
    if (!job_id_str || !*job_id_str) job_id_str = job_id_buf;

    char copies_buf[16];
    snprintf(copies_buf, sizeof(copies_buf), "%d", copies);

    /* Reconstitue les options IPP au format CUPS legacy
     * (key=value séparés par espace) — amicale-broker.py les parse. */
    char options[512] = "";
    pappl_pr_options_t *opts = papplJobCreatePrintOptions(job, INT_MAX, true);
    if (opts) {
        const char *color = (opts->print_color_mode == IPP_COLOR_MODE_MONOCHROME)
                            ? "monochrome" : "color";
        const char *sides = "one-sided";
        if (opts->sides == IPP_SIDES_TWO_SIDED_LONG_EDGE)  sides = "two-sided-long-edge";
        if (opts->sides == IPP_SIDES_TWO_SIDED_SHORT_EDGE) sides = "two-sided-short-edge";
        snprintf(options, sizeof(options),
                 "print-color-mode=%s sides=%s", color, sides);
        papplJobDeletePrintOptions(opts);
    }

    /* Récupère le password (print_token) depuis la map alimentée par auth_cb. */
    char password[256];
    cred_get(user, password, sizeof(password));

    papplJobSetImpressions(job, 1);  /* PAPPL veut un compte ; on ne sait pas
                                        encore, le backend le calculera */

    /* Construit argv pour popen. */
    char *const argv[] = {
        (char *)CLAUDINE_BROKER_PATH,
        job_id_buf,
        (char *)user,
        (char *)job_name,
        copies_buf,
        options,
        (char *)spool_path,
        NULL,
    };

    /* Construit env : on ne preserve PAS l'env du parent (qui contient les
     * AMICALE_*) parce qu'execve avec env explicite est plus déterministe.
     * AUTH_PASSWORD est l'élément critique. AMICALE_* sont aussi lus depuis
     * /etc/claudine/amicale.env par le script (filet de sécurité). */
    char auth_password[300];
    char auth_username[200];
    char printer_env[64];
    snprintf(auth_password, sizeof(auth_password), "AUTH_PASSWORD=%s", password);
    snprintf(auth_username, sizeof(auth_username), "AUTH_USERNAME=%s", user);
    snprintf(printer_env,   sizeof(printer_env),   "PRINTER=%s", CLAUDINE_PRINTER_NAME);

    /* On hérite l'env du parent + on overrides AUTH_*. Plus simple que
     * reconstruire intégralement. */
    setenv("AUTH_PASSWORD", password, 1);
    setenv("AUTH_USERNAME", user, 1);
    setenv("PRINTER", CLAUDINE_PRINTER_NAME, 1);

    pid_t pid = fork();
    if (pid < 0) {
        papplJobSetReasons(job, PAPPL_JREASON_OTHER, PAPPL_JREASON_NONE);
        papplLogJob(job, PAPPL_LOGLEVEL_ERROR, "fork() failed: %s", strerror(errno));
        return false;
    }

    if (pid == 0) {
        /* Enfant : execve. */
        execv(CLAUDINE_BROKER_PATH, argv);
        /* Si execv revient, c'est qu'il a échoué. */
        fprintf(stderr, "execv %s failed: %s\n", CLAUDINE_BROKER_PATH, strerror(errno));
        _exit(127);
    }

    /* Parent : attend la fin du subprocess. */
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) {
        papplLogJob(job, PAPPL_LOGLEVEL_ERROR, "waitpid failed: %s", strerror(errno));
        return false;
    }

    int rc = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
    papplLogJob(job, PAPPL_LOGLEVEL_INFO,
                "amicale-broker exited rc=%d (job-id=%d user=%s)",
                rc, job_id, user);

    switch (rc) {
        case BROKER_OK:
            return true;
        case BROKER_AUTH_REQUIRED:
        case BROKER_FAILED:
        case BROKER_STOP:
        case BROKER_CANCEL:
            papplJobSetReasons(job, PAPPL_JREASON_JOB_CANCELED_BY_OPERATOR,
                               PAPPL_JREASON_NONE);
            return false;
        case BROKER_HOLD:
            papplJobHold(job, "indefinite");
            return false;
        case BROKER_RETRY:
        default:
            papplJobSetReasons(job, PAPPL_JREASON_OTHER, PAPPL_JREASON_NONE);
            return false;
    }
}

/* ─── System callback (création du système) ────────────────────────────── */

static const char *claudine_drivers_list[] = { CLAUDINE_DRIVER_NAME };

static pappl_system_t *claudine_system_cb(int num_options,
                                           cups_option_t *options,
                                           void *data)
{
    (void)num_options;
    (void)options;
    (void)data;

    pappl_system_t *system = papplSystemCreate(
        PAPPL_SOPTIONS_MULTI_QUEUE | PAPPL_SOPTIONS_DNSSD_HOST,
        "Claudine — l'Amicale du WiFi",
        CLAUDINE_LISTEN_PORT,
        "ipp,ipps,http,https",
        CLAUDINE_SPOOL_DIR,
        "claudine.log",
        "DEBUG",
        CLAUDINE_AUTH_SERVICE,
        false                       /* TLS-only ? non, Caddy gère TLS */
    );

    if (!system) {
        fprintf(stderr, "claudine: papplSystemCreate failed\n");
        return NULL;
    }

    /* Drivers + auto-add */
    papplSystemSetPrinterDrivers(system,
        sizeof(claudine_drivers_list) / sizeof(claudine_drivers_list[0]),
        claudine_drivers_list,
        claudine_autoadd_cb,
        NULL,
        claudine_driver_cb,
        NULL);

    /* Auth callback custom (accept-all + stash password) */
    papplSystemSetAuthCallback(system, "Claudine", claudine_auth_cb, NULL);

    /* Création de l'imprimante "Claudine" elle-même */
    pappl_printer_t *printer = papplPrinterCreate(
        system,
        0,                          /* printer-id auto */
        CLAUDINE_DRIVER_NAME,
        CLAUDINE_PRINTER_NAME,
        CLAUDINE_DEVICE_URI,
        NULL                        /* device-id auto depuis driver */
    );
    if (!printer) {
        fprintf(stderr, "claudine: papplPrinterCreate failed\n");
        papplSystemDelete(system);
        return NULL;
    }

    /* Process callback custom (delegate à amicale-broker.py) */
    papplPrinterSetProcessJobCallback(printer, claudine_process_cb);

    return system;
}

/* ─── main ─────────────────────────────────────────────────────────────── */

int main(int argc, char *argv[])
{
    return papplMainloop(argc, argv,
                         "1.0",                  /* version */
                         NULL,                   /* footer HTML */
                         sizeof(claudine_drivers_list) /
                             sizeof(claudine_drivers_list[0]),
                         claudine_drivers_list,
                         claudine_autoadd_cb,
                         claudine_driver_cb,
                         "claudine",             /* subcmd_name */
                         NULL,                   /* subcmd_cb */
                         claudine_system_cb,
                         NULL,                   /* usage_cb */
                         NULL                    /* data */
    );
}
