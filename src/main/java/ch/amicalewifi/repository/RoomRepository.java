package ch.amicalewifi.repository;

import ch.amicalewifi.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoomRepository extends JpaRepository<Room, UUID> {
    List<Room>     findByActiveTrue();
    Optional<Room> findByQrCodeToken(String token);
    Optional<Room> findByKoalendarSlug(String koalendarSlug);
}
