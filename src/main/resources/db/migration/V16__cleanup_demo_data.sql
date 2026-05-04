-- Remove demo data inserted by V2 (keep admin user only)

DELETE FROM printer_jobs WHERE member_id IN (
    SELECT id FROM members WHERE email IN (
        'camille@exemple.ch','paul@exemple.ch','laurent@company.ch',
        'pierre@mail.ch','nadia@freelance.ch'));

DELETE FROM presences WHERE member_id IN (
    SELECT id FROM members WHERE email IN (
        'camille@exemple.ch','paul@exemple.ch','laurent@company.ch',
        'pierre@mail.ch','nadia@freelance.ch'));

DELETE FROM room_bookings WHERE member_id IN (
    SELECT id FROM members WHERE email IN (
        'camille@exemple.ch','paul@exemple.ch','laurent@company.ch',
        'pierre@mail.ch','nadia@freelance.ch'));

DELETE FROM access_events WHERE member_id IN (
    SELECT id FROM members WHERE email IN (
        'camille@exemple.ch','paul@exemple.ch','laurent@company.ch',
        'pierre@mail.ch','nadia@freelance.ch'))
    OR badge_uid = 'NFC-9B01';

UPDATE members SET user_id = NULL WHERE email IN (
    'camille@exemple.ch','paul@exemple.ch','laurent@company.ch');

DELETE FROM members WHERE email IN (
    'camille@exemple.ch','paul@exemple.ch','laurent@company.ch',
    'pierre@mail.ch','nadia@freelance.ch');

DELETE FROM users WHERE email IN (
    'camille@exemple.ch','paul@exemple.ch','laurent@company.ch',
    'borne@amicalewifi.ch');
