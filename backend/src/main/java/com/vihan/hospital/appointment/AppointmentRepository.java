package com.vihan.hospital.appointment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

	Optional<Appointment> findByBookingRef(String bookingRef);

	boolean existsByBookingRef(String bookingRef);

	/** Active (booked or checked-in) appointments of a mobile on a date, optionally ignoring one (reschedule). */
	@Query("""
			select count(a) from Appointment a
			where a.patientMobile = :mobile and a.apptDate = :date
			  and a.status in (com.vihan.hospital.appointment.Appointment.Status.BOOKED,
			                   com.vihan.hospital.appointment.Appointment.Status.CHECKED_IN)
			  and (:excludeId is null or a.id <> :excludeId)""")
	long countActiveForMobileOnDate(@Param("mobile") String mobile, @Param("date") LocalDate date,
			@Param("excludeId") Long excludeId);

	/** Reception's day list (FR-R1); null filters mean "all". */
	@Query("""
			select a from Appointment a
			where a.apptDate = :date
			  and (:branchId is null or a.branchId = :branchId)
			  and (:doctorId is null or a.doctorId = :doctorId)
			  and (:status is null or a.status = :status)""")
	List<Appointment> findForDay(@Param("date") LocalDate date, @Param("branchId") Long branchId,
			@Param("doctorId") Long doctorId, @Param("status") Appointment.Status status);

	/** FR-R7: upcoming bookings hit by leave / holiday that reception still has to phone about. */
	@Query("""
			select a from Appointment a
			where a.needsAction = true and a.status = com.vihan.hospital.appointment.Appointment.Status.BOOKED
			  and a.apptDate >= :from and (:branchId is null or a.branchId = :branchId)
			order by a.startAt""")
	List<Appointment> findNeedingAction(@Param("from") LocalDate from, @Param("branchId") Long branchId);

	/** [mobile, count] of no-shows since a date, for the "often doesn't come" flag. */
	@Query("""
			select a.patientMobile, count(a) from Appointment a
			where a.status = com.vihan.hospital.appointment.Appointment.Status.NO_SHOW
			  and a.apptDate >= :since and a.patientMobile in :mobiles
			group by a.patientMobile""")
	List<Object[]> countNoShows(@Param("mobiles") Collection<String> mobiles, @Param("since") LocalDate since);

	/** Highest token given to a walk-in for a doctor at a branch on a day. */
	@Query("""
			select max(a.tokenNo) from Appointment a
			where a.doctorId = :doctorId and a.branchId = :branchId and a.apptDate = :date""")
	Optional<Integer> findMaxToken(@Param("doctorId") Long doctorId, @Param("branchId") Long branchId,
			@Param("date") LocalDate date);

	/** Serialises walk-in token numbers for one doctor (row lock until the transaction ends). */
	@Query(value = "SELECT doctor_id FROM doctor WHERE doctor_id = :id FOR UPDATE", nativeQuery = true)
	Long lockDoctor(@Param("id") Long doctorId);

	/** End-of-day job (DESIGN.md §10.4): bookings nobody checked in become NO_SHOW. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE appointment SET status = 'NO_SHOW', needs_action = FALSE, version = version + 1, updated_at = LOCALTIMESTAMP
			WHERE status = 'BOOKED' AND appt_date <= :lastDate""", nativeQuery = true)
	int markNoShowsUpTo(@Param("lastDate") LocalDate lastDate);

	/** [branchId, doctorId, status] of every appointment on a day, for the dashboard. */
	@Query("select a.branchId, a.doctorId, a.status from Appointment a where a.apptDate = :date")
	List<Object[]> statusesOn(@Param("date") LocalDate date);

	/** A patient's coming appointments (linked at booking via UHID, or at check-in). */
	@Query("""
			select a from Appointment a
			where a.patientId = :patientId and a.apptDate >= :from
			  and a.status in (com.vihan.hospital.appointment.Appointment.Status.BOOKED,
			                   com.vihan.hospital.appointment.Appointment.Status.CHECKED_IN)
			order by a.apptDate, a.startAt""")
	List<Appointment> findUpcomingForPatient(@Param("patientId") Long patientId, @Param("from") LocalDate from);

}
