package app.closure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface NeaOpenDayRepository extends JpaRepository<NeaOpenDay, Long> {

    List<NeaOpenDay> findByOwnerId(Long ownerId);

    boolean existsByOwnerIdAndOpenDate(Long ownerId, LocalDate openDate);

    @Modifying
    @Query("DELETE FROM NeaOpenDay d WHERE d.owner.id = :ownerId AND d.openDate BETWEEN :from AND :to")
    void deleteBetween(@Param("ownerId") Long ownerId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Modifying
    @Query("DELETE FROM NeaOpenDay d WHERE d.owner.id = :ownerId")
    void deleteAllForOwner(@Param("ownerId") Long ownerId);
}
