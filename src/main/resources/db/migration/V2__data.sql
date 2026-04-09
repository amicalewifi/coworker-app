-- ============================================================
-- V2__data.sql  —  Données de démonstration
-- admin2024 / member2024 (bcrypt)
-- ============================================================

INSERT INTO users (email, password_hash, role) VALUES
    ('admin@amicalewifi.ch',  '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8RjB3X8MeSQkFBvlxy', 'ADMIN'),
    ('camille@exemple.ch',    '$2a$10$slxzckbdnKpPDJV/R2yBxeNSNEEeZnRtGFe37xnZRiRWt.XQPD/Ce', 'MEMBER'),
    ('paul@exemple.ch',       '$2a$10$slxzckbdnKpPDJV/R2yBxeNSNEEeZnRtGFe37xnZRiRWt.XQPD/Ce', 'MEMBER'),
    ('laurent@company.ch',    '$2a$10$slxzckbdnKpPDJV/R2yBxeNSNEEeZnRtGFe37xnZRiRWt.XQPD/Ce', 'MEMBER'),
    ('borne@amicalewifi.ch',  '$2a$10$slxzckbdnKpPDJV/R2yBxeNSNEEeZnRtGFe37xnZRiRWt.XQPD/Ce', 'TERMINAL');

INSERT INTO members (first_name,last_name,email,phone,badge_uid,badge_active,
    membership,pack_units_total,pack_units_used,pack_expires,
    conf_credits_total_h,conf_credits_used_h,print_quota,user_id)
VALUES
    ('Camille','Berrut','camille@exemple.ch','+41 79 111 22 33',
     'NFC-3C1A',TRUE,'PACK_10J',10.0,6.5,CURRENT_DATE+INTERVAL '13 days',
     4.0,2.5,50,(SELECT id FROM users WHERE email='camille@exemple.ch')),

    ('Paul','Germanier','paul@exemple.ch','+41 79 222 33 44',
     'NFC-A4F1',TRUE,'PACK_5J',5.0,4.0,CURRENT_DATE+INTERVAL '6 days',
     2.0,1.5,50,(SELECT id FROM users WHERE email='paul@exemple.ch')),

    ('Laurent','Mabillard','laurent@company.ch','+41 79 333 44 55',
     'NFC-7F22',TRUE,'PERMANENT',NULL,0.0,NULL,
     10.0,3.0,100,(SELECT id FROM users WHERE email='laurent@company.ch')),

    ('Pierre','Sauthier','pierre@mail.ch',NULL,
     'NFC-2D88',TRUE,'PACK_5J',5.0,5.0,CURRENT_DATE-INTERVAL '2 days',
     2.0,2.0,50,NULL),

    ('Nadia','Cornut','nadia@freelance.ch',NULL,
     'NFC-9B01',TRUE,'JOURNEE_ESSAI',NULL,0.0,NULL,0.0,0.0,50,NULL);

INSERT INTO presences (member_id,date,presence_type,status,checked_in_at,units_consumed,is_unitaire)
VALUES
    ((SELECT id FROM members WHERE email='camille@exemple.ch'),
     CURRENT_DATE,'FULL_DAY','ACTIVE',NOW()-INTERVAL '2 hours',1.0,FALSE),
    ((SELECT id FROM members WHERE email='paul@exemple.ch'),
     CURRENT_DATE,'HALF_AM','ACTIVE',NOW()-INTERVAL '3 hours',0.5,FALSE),
    ((SELECT id FROM members WHERE email='laurent@company.ch'),
     CURRENT_DATE,'FULL_DAY','ACTIVE',NOW()-INTERVAL '4 hours',1.0,FALSE);

INSERT INTO room_bookings (room_id,member_id,organizer_name,date,start_time,end_time,participants,title,status,billed_from_credits)
VALUES ((SELECT id FROM rooms WHERE name='Salle de conférence'),
        (SELECT id FROM members WHERE email='laurent@company.ch'),
        'Laurent Mabillard',CURRENT_DATE,'14:00','16:00',8,'Réunion mensuelle','CONFIRMED',TRUE);

INSERT INTO printer_jobs (member_id,filename,pages,copies,status)
VALUES ((SELECT id FROM members WHERE email='camille@exemple.ch'),'rapport.pdf',8,1,'PRINTING'),
       ((SELECT id FROM members WHERE email='laurent@company.ch'),'facture.pdf',2,1,'QUEUED');

INSERT INTO printer_status (toner_black_pct,drum_pct,paper_tray1_pct,paper_tray2_pct)
VALUES (68,82,45,12);

INSERT INTO access_events (member_id,badge_uid,event_type,presence_type,units_consumed,occurred_at)
VALUES
    ((SELECT id FROM members WHERE email='camille@exemple.ch'),
     'NFC-3C1A','ENTRY_GRANTED','FULL_DAY',1.0,NOW()-INTERVAL '2 hours'),
    ((SELECT id FROM members WHERE email='paul@exemple.ch'),
     'NFC-A4F1','ENTRY_GRANTED','HALF_AM',0.5,NOW()-INTERVAL '3 hours'),
    ((SELECT id FROM members WHERE email='laurent@company.ch'),
     'NFC-7F22','ENTRY_GRANTED','FULL_DAY',1.0,NOW()-INTERVAL '4 hours'),
    (NULL,'NFC-9B01','NEW_MEMBER_CREATED',NULL,NULL,NOW()-INTERVAL '5 hours'),
    ((SELECT id FROM members WHERE email='pierre@mail.ch'),
     'NFC-2D88','ENTRY_DENIED','FULL_DAY',NULL,NOW()-INTERVAL '6 hours');
