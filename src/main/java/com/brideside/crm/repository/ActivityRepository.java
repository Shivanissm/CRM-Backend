package com.brideside.crm.repository;

import com.brideside.crm.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ActivityRepository extends JpaRepository<Activity, Long>, JpaSpecificationExecutor<Activity> {
    List<Activity> findByAssignedUserId(Long assignedUserId);

    List<Activity> findByDealId(Long dealId);

    /** Open activities for a deal (done flag false). */
    List<Activity> findByDealIdAndDoneFalse(Long dealId);
    
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE activities SET assigned_user_id = NULL WHERE assigned_user_id = :userId", nativeQuery = true)
    void clearAssignedUserIdByUserId(@Param("userId") Long userId);

    // Bulk detach organization from activities for a given organization
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE activities SET organization_id = NULL WHERE organization_id = :organizationId", nativeQuery = true)
    void clearOrganizationIdByOrganizationId(@Param("organizationId") Long organizationId);

    // Bulk delete activities by organization (hard delete)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM activities WHERE organization_id = :organizationId", nativeQuery = true)
    void deleteActivitiesByOrganizationId(@Param("organizationId") Long organizationId);

    // Find all activities for a given person ID
    @Query("SELECT a FROM Activity a WHERE a.personId = :personId")
    List<Activity> findByPersonId(@Param("personId") Long personId);

    // Bulk update person ID for activities
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE activities SET person_id = :targetPersonId WHERE person_id = :sourcePersonId", nativeQuery = true)
    int updatePersonId(@Param("sourcePersonId") Long sourcePersonId, @Param("targetPersonId") Long targetPersonId);
}


