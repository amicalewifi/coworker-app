package ch.amicalewifi.repository;

import ch.amicalewifi.model.BookingStatus;
import ch.amicalewifi.model.RoomBooking;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface RoomBookingRepository extends JpaRepository<RoomBooking, UUID> {

    @EntityGraph(attributePaths = {"room", "member"})
    List<RoomBooking> findByDateAndStatusOrderByStartTime(LocalDate date, BookingStatus status);

    @EntityGraph(attributePaths = {"room", "member"})
    List<RoomBooking> findByRoomIdAndDateOrderByStartTime(UUID roomId, LocalDate date);

    @EntityGraph(attributePaths = {"room", "member"})
    List<RoomBooking> findByMemberIdOrderByDateDescStartTimeDesc(UUID memberId);

    @EntityGraph(attributePaths = {"room", "member"})
    List<RoomBooking> findByDateGreaterThanEqualAndStatusOrderByDateAscStartTimeAsc(LocalDate date, BookingStatus status);

    @EntityGraph(attributePaths = {"room", "member"})
    List<RoomBooking> findByDateBetweenAndStatusOrderByDateAscStartTimeAsc(LocalDate from, LocalDate to, BookingStatus status);

    @Query("""
        SELECT b FROM RoomBooking b
        WHERE b.room.id = :rid AND b.date = :date AND b.status = 'CONFIRMED'
          AND b.startTime < :end AND b.endTime > :start
    """)
    List<RoomBooking> findConflicts(@Param("rid")   UUID rid,
                                    @Param("date")  LocalDate date,
                                    @Param("start") LocalTime start,
                                    @Param("end")   LocalTime end);
}
