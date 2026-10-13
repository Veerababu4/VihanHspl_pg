package com.vihan.hospital.patient;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientRepository extends JpaRepository<Patient, Long> {

	@Query(value = "SELECT nextval('seq_uhid')", nativeQuery = true)
	long nextUhidNumber();

	/** Check-in suggestions (DESIGN.md §19.5): same mobile, or the same name (any mobile). */
	@Query("""
			select p from Patient p
			where p.active = true and (p.mobile = :mobile or upper(p.fullName) = upper(:name))
			order by case when p.mobile = :mobile then 0 else 1 end, p.fullName""")
	List<Patient> findCandidates(@Param("mobile") String mobile, @Param("name") String name);

	List<Patient> findTop20ByActiveTrueAndMobileOrderByFullName(String mobile);

	Optional<Patient> findByUhidAndActiveTrue(String uhid);

	/** Active or not: an Aadhaar can belong to one record only (unique index). */
	Optional<Patient> findByAadhaarHash(String aadhaarHash);

	/** Name search (FR-PT3): the text anywhere in the name, ignoring case. */
	@Query("""
			select p from Patient p
			where p.active = true and upper(p.fullName) like concat('%', upper(:text), '%')
			order by p.fullName""")
	List<Patient> searchByName(@Param("text") String text, Pageable limit);

}
