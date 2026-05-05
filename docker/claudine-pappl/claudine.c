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
 *   3. auth_cb() accepte n'importe quel user/password au niveau IPP. Pour
 *      retrouver le password en aval (job processing), on lit le header
 *      HTTP Authorization brut depuis le client et on stash dans une map
 *      mutex-protégée username→password (TTL 10 min).
 *   4. printfile_cb() est invoqué par PAPPL pour chaque job PDF. Il
 *      construit argv/env compatibles avec amicale-broker.py et popen()
 *      le script Python, en respectant strictement le contrat existant.
 *
 * Build : voir Makefile (utilise libpappl, libcups via pkg-config).
 * Run   : /usr/local/bin/claudine server (foreground, géré par entrypoint.sh)
 */

#include <pappl/pappl.h>
#include <ctype.h>
#include <errno.h>
#include <limits.h>
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
#define CLAUDINE_DEVICE_URI        "file:///dev/null"
#define CLAUDINE_DEVICE_ID         "MFG:Amicale du WiFi;MDL:Claudine;CMD:PWGRaster,PDF;CLS:PRINTER;DRV:DM_IPP,R0,M0,LM_IPP_Everywhere;"
#define CLAUDINE_BROKER_PATH       "/usr/local/bin/amicale-broker"
#define CLAUDINE_LISTEN_PORT       8000
#define CLAUDINE_SPOOL_DIR         "/var/lib/pappl"
#define CLAUDINE_AUTH_SCHEME       "basic"

/* Codes de sortie hérités CUPS retournés par amicale-broker.py. */
#define BROKER_OK                  0
#define BROKER_FAILED              1
#define BROKER_AUTH_REQUIRED       2
#define BROKER_HOLD                3
#define BROKER_STOP                4
#define BROKER_CANCEL              5
#define BROKER_RETRY               6

/* ─── Map username → password (auth bridge) ────────────────────────────── */

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
        if (g_creds[i].username[0] != '\0' &&
            strncmp(g_creds[i].username, user, sizeof(g_creds[i].username)) == 0) {
            slot = i;
            break;
        }
        if (slot < 0 && g_creds[i].username[0] == '\0') slot = i;
        if (g_creds[i].ts < oldest_ts) {
            oldest_ts = g_creds[i].ts;
            oldest = i;
        }
    }
    if (slot < 0) slot = oldest;
    snprintf(g_creds[slot].username, sizeof(g_creds[slot].username), "%s", user);
    snprintf(g_creds[slot].password, sizeof(g_creds[slot].password), "%s", pass);
    g_creds[slot].ts = now;
    pthread_mutex_unlock(&g_creds_mu);
}

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

/* ─── Base64 decoder (Basic auth header) ───────────────────────────────── */

static int b64_val(int c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

static int b64_decode(const char *in, char *out, size_t out_cap)
{
    size_t out_n = 0;
    int v[4];
    int i = 0;
    for (const char *p = in; *p && out_n + 1 < out_cap; p++) {
        if (*p == '=' || isspace((unsigned char)*p)) continue;
        int n = b64_val((unsigned char)*p);
        if (n < 0) return -1;
        v[i++] = n;
        if (i == 4) {
            if (out_n + 3 > out_cap) return -1;
            out[out_n++] = (v[0] << 2) | (v[1] >> 4);
            out[out_n++] = ((v[1] & 0xF) << 4) | (v[2] >> 2);
            out[out_n++] = ((v[2] & 0x3) << 6) | v[3];
            i = 0;
        }
    }
    /* Trailing partial group (= padding stripped) */
    if (i == 2 && out_n + 1 < out_cap) {
        out[out_n++] = (v[0] << 2) | (v[1] >> 4);
    } else if (i == 3 && out_n + 2 < out_cap) {
        out[out_n++] = (v[0] << 2) | (v[1] >> 4);
        out[out_n++] = ((v[1] & 0xF) << 4) | (v[2] >> 2);
    }
    out[out_n] = '\0';
    return 0;
}

/* ─── Auth callback ────────────────────────────────────────────────────── */
/*
 * PAPPL invoque ce callback pour chaque requête HTTP qui tombe sur une route
 * protégée (printer endpoints). Signature 4-args (PAPPL ≥ 1.4).
 *
 * On ne valide pas les credentials ici (la vraie auth est dans amicale-broker
 * via /api/v1/print/submit). On profite du passage pour extraire username +
 * password du header Authorization brut et les stash dans la map globale.
 */
static http_status_t claudine_auth_cb(pappl_client_t *client,
                                       const char *resource,
                                       unsigned int reasons,
                                       void *data)
{
    (void)resource;
    (void)reasons;
    (void)data;

    http_t *http = papplClientGetHTTP(client);
    if (!http) return HTTP_STATUS_CONTINUE;

    const char *authz = httpGetField(http, HTTP_FIELD_AUTHORIZATION);
    if (!authz || !*authz) return HTTP_STATUS_CONTINUE;

    /* "Basic <base64(user:pass)>" */
    if (strncasecmp(authz, "Basic ", 6) == 0) {
        const char *b64 = authz + 6;
        while (*b64 == ' ') b64++;
        char decoded[512];
        if (b64_decode(b64, decoded, sizeof(decoded)) == 0) {
            char *colon = strchr(decoded, ':');
            if (colon) {
                *colon = '\0';
                cred_put(decoded, colon + 1);
            }
        }
    }
    return HTTP_STATUS_CONTINUE;
}

/* ─── printfile_cb : process job (PDF déjà spoulé par PAPPL) ───────────── */

static bool claudine_printfile_cb(pappl_job_t *job,
                                   pappl_pr_options_t *options,
                                   pappl_device_t *device)
{
    (void)device;

    int         job_id      = papplJobGetID(job);
    const char *user        = papplJobGetUsername(job);
    const char *job_name    = papplJobGetName(job);
    int         copies      = papplJobGetCopies(job);
    const char *spool_path  = papplJobGetFilename(job);

    if (!user)       user      = "";
    if (!job_name)   job_name  = "untitled";
    if (!spool_path) spool_path = "";
    if (copies < 1)  copies    = 1;

    char job_id_buf[32];
    snprintf(job_id_buf, sizeof(job_id_buf), "%d", job_id);
    char copies_buf[16];
    snprintf(copies_buf, sizeof(copies_buf), "%d", copies);

    /* Reconstitue les options IPP au format CUPS legacy. */
    char options_str[512] = "";
    if (options) {
        const char *color = (options->print_color_mode == PAPPL_COLOR_MODE_MONOCHROME)
                            ? "monochrome" : "color";
        const char *sides = "one-sided";
        if (options->sides == PAPPL_SIDES_TWO_SIDED_LONG_EDGE)  sides = "two-sided-long-edge";
        if (options->sides == PAPPL_SIDES_TWO_SIDED_SHORT_EDGE) sides = "two-sided-short-edge";
        snprintf(options_str, sizeof(options_str),
                 "print-color-mode=%s sides=%s", color, sides);
    }

    /* Récupère le password depuis la map alimentée par auth_cb. */
    char password[256];
    cred_get(user, password, sizeof(password));

    /* Set env pour le subprocess. setenv() est process-wide, donc le risque
     * de course est réel si plusieurs jobs tournent en parallèle. PAPPL
     * sérialise par défaut le processing par printer (un job à la fois sur
     * Claudine), donc on est tranquille. */
    setenv("AUTH_PASSWORD", password, 1);
    setenv("AUTH_USERNAME", user, 1);
    setenv("PRINTER", CLAUDINE_PRINTER_NAME, 1);

    char *const argv[] = {
        (char *)CLAUDINE_BROKER_PATH,
        job_id_buf,
        (char *)user,
        (char *)job_name,
        copies_buf,
        options_str,
        (char *)spool_path,
        NULL,
    };

    pid_t pid = fork();
    if (pid < 0) {
        papplLogJob(job, PAPPL_LOGLEVEL_ERROR,
                    "fork() failed: %s", strerror(errno));
        return false;
    }
    if (pid == 0) {
        execv(CLAUDINE_BROKER_PATH, argv);
        fprintf(stderr, "execv %s failed: %s\n",
                CLAUDINE_BROKER_PATH, strerror(errno));
        _exit(127);
    }

    int status = 0;
    if (waitpid(pid, &status, 0) < 0) {
        papplLogJob(job, PAPPL_LOGLEVEL_ERROR,
                    "waitpid failed: %s", strerror(errno));
        return false;
    }

    int rc = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
    papplLogJob(job, PAPPL_LOGLEVEL_INFO,
                "amicale-broker exited rc=%d (job-id=%d user=%s)",
                rc, job_id, user);

    /* Mapping exit-code → résultat PAPPL (true = OK, false = FAILED). */
    return rc == BROKER_OK;
}

/* ─── Driver capabilities ──────────────────────────────────────────────── */

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

    snprintf(driver_data->make_and_model, sizeof(driver_data->make_and_model),
             "Amicale du WiFi Claudine");
    driver_data->ppm        = 22;
    driver_data->ppm_color  = 22;
    driver_data->kind       = PAPPL_KIND_DOCUMENT;

    /* Custom callback pour le print : invoque amicale-broker.py. */
    driver_data->printfile_cb = claudine_printfile_cb;

    /* Formats acceptés (PAPPL convertit automatiquement). */
    driver_data->format     = "application/pdf";
    driver_data->num_type   = 4;
    driver_data->type[0]    = "application/pdf";
    driver_data->type[1]    = "image/pwg-raster";
    driver_data->type[2]    = "image/urf";
    driver_data->type[3]    = "image/jpeg";

    /* Résolutions. */
    driver_data->num_resolution  = 2;
    driver_data->x_resolution[0] = 300; driver_data->y_resolution[0] = 300;
    driver_data->x_resolution[1] = 600; driver_data->y_resolution[1] = 600;
    driver_data->x_default = driver_data->y_default = 300;

    /* Color modes. */
    driver_data->color_supported = PAPPL_COLOR_MODE_AUTO
                                 | PAPPL_COLOR_MODE_COLOR
                                 | PAPPL_COLOR_MODE_MONOCHROME;
    driver_data->color_default   = PAPPL_COLOR_MODE_AUTO;

    /* Duplex. */
    driver_data->sides_supported = PAPPL_SIDES_ONE_SIDED
                                 | PAPPL_SIDES_TWO_SIDED_LONG_EDGE
                                 | PAPPL_SIDES_TWO_SIDED_SHORT_EDGE;
    driver_data->sides_default   = PAPPL_SIDES_ONE_SIDED;

    /* Quality. */
    driver_data->quality_default = IPP_QUALITY_NORMAL;

    /* Médias : A4 + Letter. */
    driver_data->num_media = 2;
    driver_data->media[0]  = "iso_a4_210x297mm";
    driver_data->media[1]  = "na_letter_8.5x11in";

    driver_data->num_source = 1;
    driver_data->source[0]  = "auto";

    /* Médias chargés. */
    snprintf(driver_data->media_ready[0].size_name,
             sizeof(driver_data->media_ready[0].size_name),
             "iso_a4_210x297mm");
    driver_data->media_ready[0].size_width  = 21000;
    driver_data->media_ready[0].size_length = 29700;
    driver_data->media_ready[0].bottom_margin = 423;
    driver_data->media_ready[0].top_margin    = 423;
    driver_data->media_ready[0].left_margin   = 423;
    driver_data->media_ready[0].right_margin  = 423;
    snprintf(driver_data->media_ready[0].source,
             sizeof(driver_data->media_ready[0].source), "auto");
    snprintf(driver_data->media_ready[0].type,
             sizeof(driver_data->media_ready[0].type), "stationery");
    memcpy(&driver_data->media_default, &driver_data->media_ready[0],
           sizeof(driver_data->media_default));

    /* Output bin. */
    driver_data->num_bin     = 1;
    driver_data->bin[0]      = "face-down";
    driver_data->bin_default = 0;

    driver_data->finishings = PAPPL_FINISHINGS_NONE;

    return true;
}

/* ─── Auto-add callback ────────────────────────────────────────────────── */

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

/* ─── Drivers list ─────────────────────────────────────────────────────── */

static pappl_pr_driver_t claudine_drivers[] = {
    {
        .name        = CLAUDINE_DRIVER_NAME,
        .description = "Amicale du WiFi Claudine",
        .device_id   = CLAUDINE_DEVICE_ID,
        .extension   = NULL,
    },
};

/* ─── System callback (création du système) ────────────────────────────── */

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
        "_print,_universal",
        CLAUDINE_SPOOL_DIR,
        "claudine.log",
        PAPPL_LOGLEVEL_DEBUG,
        NULL,                       /* auth_service : on a notre callback */
        false                       /* tls_only : non, Caddy gère TLS */
    );
    if (!system) {
        fprintf(stderr, "claudine: papplSystemCreate failed\n");
        return NULL;
    }

    papplSystemSetPrinterDrivers(
        system,
        sizeof(claudine_drivers) / sizeof(claudine_drivers[0]),
        claudine_drivers,
        claudine_autoadd_cb,
        NULL,                       /* create_cb */
        claudine_driver_cb,
        NULL                        /* data */
    );

    papplSystemSetAuthCallback(system, CLAUDINE_AUTH_SCHEME,
                                claudine_auth_cb, NULL);

    /* Crée la printer "Claudine". Argument order PAPPL :
     *   (system, printer_id, printer_name, driver_name, device_id, device_uri)
     * (l'ordre est subtil — driver_name vient APRÈS printer_name, et
     * device_id avant device_uri). */
    pappl_printer_t *printer = papplPrinterCreate(
        system,
        0,                          /* printer-id auto */
        CLAUDINE_PRINTER_NAME,
        CLAUDINE_DRIVER_NAME,
        CLAUDINE_DEVICE_ID,
        CLAUDINE_DEVICE_URI
    );
    if (!printer) {
        fprintf(stderr, "claudine: papplPrinterCreate failed\n");
        papplSystemDelete(system);
        return NULL;
    }

    return system;
}

/* ─── main ─────────────────────────────────────────────────────────────── */

int main(int argc, char *argv[])
{
    return papplMainloop(
        argc, argv,
        "1.0",                      /* version */
        NULL,                       /* footer HTML */
        sizeof(claudine_drivers) / sizeof(claudine_drivers[0]),
        claudine_drivers,
        claudine_autoadd_cb,
        claudine_driver_cb,
        "claudine",                 /* subcmd_name */
        NULL,                       /* subcmd_cb */
        claudine_system_cb,
        NULL,                       /* usage_cb */
        NULL                        /* data */
    );
}
