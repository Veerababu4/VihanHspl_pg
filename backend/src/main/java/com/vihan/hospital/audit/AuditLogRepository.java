package com.vihan.hospital.audit;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vihan.hospital.audit.AuditService.AuditLogDto;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

	/**
	 * Audit viewer (FR-AD5): every filter is optional; newest first. The casts give PostgreSQL a
	 * type for a null date ("could not determine data type of parameter" otherwise).
	 */
	@Query(value = """
			select new com.vihan.hospital.audit.AuditService$AuditLogDto(
			    a.id, a.createdAt, a.userId, u.username, a.action, a.entityType, a.entityId, a.details, a.ipAddress)
			from AuditLog a left join AppUser u on u.id = a.userId
			where (:userId is null or a.userId = :userId)
			  and (:action is null or a.action = :action)
			  and (cast(:from as LocalDateTime) is null or a.createdAt >= :from)
			  and (cast(:to as LocalDateTime) is null or a.createdAt < :to)
			order by a.createdAt desc, a.id desc""",
			countQuery = """
			select count(a) from AuditLog a
			where (:userId is null or a.userId = :userId)
			  and (:action is null or a.action = :action)
			  and (cast(:from as LocalDateTime) is null or a.createdAt >= :from)
			  and (cast(:to as LocalDateTime) is null or a.createdAt < :to)""")
	Page<AuditLogDto> search(@Param("userId") Long userId, @Param("action") AuditAction action,
			@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

}
