-- Update printer to Kyocera TASKalfa 352ci at 192.168.1.10
ALTER TABLE printer_status
    ALTER COLUMN model       SET DEFAULT 'Kyocera TASKalfa 352ci',
    ALTER COLUMN printer_ip  SET DEFAULT '192.168.1.10';

UPDATE printer_status
SET model      = 'Kyocera TASKalfa 352ci',
    printer_ip = '192.168.1.10';
