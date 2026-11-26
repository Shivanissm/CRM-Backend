package com.brideside.crm.repository;

import com.brideside.crm.entity.Deal;
import com.brideside.crm.entity.DealSource;
import com.brideside.crm.entity.DealSubSource;
import com.brideside.crm.entity.Pipeline;
import com.brideside.crm.entity.Stage;
import com.brideside.crm.entity.DealStatus;
import com.brideside.crm.entity.Person;
import com.brideside.crm.entity.Organization;
import com.brideside.crm.entity.Category;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

import java.time.LocalDateTime;
import java.util.List;

public interface DealRepository extends JpaRepository<Deal, Long>, JpaSpecificationExecutor<Deal> {
    List<Deal> findByPipeline(Pipeline pipeline);
    List<Deal> findByStage(Stage stage);
    List<Deal> findByStatus(DealStatus status);
    List<Deal> findByPerson(Person person);
    List<Deal> findByOrganization(Organization organization);
    List<Deal> findByDealCategory(Category category);
    List<Deal> findByCreatedByUserId(Long createdByUserId);

    @Query("select distinct d from Deal d " +
            "left join fetch d.person p " +
            "left join fetch p.owner owner " +
            "left join fetch d.organization org " +
            "left join fetch d.dealCategory cat " +
            "left join fetch d.source src " +
            "left join fetch d.owner " +
            "where d.status = com.brideside.crm.entity.DealStatus.WON " +
            "and (d.isDeleted = false or d.isDeleted is null) " +
            "and ( (d.wonAt is not null and d.wonAt >= :start and d.wonAt < :end) " +
            "   or (d.wonAt is null and ( (d.updatedAt is not null and d.updatedAt >= :start and d.updatedAt < :end) or (d.updatedAt is null and d.createdAt >= :start and d.createdAt < :end) ) ) )")
    List<Deal> findWonDealsUpdatedBetween(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
    List<Deal> findByReferencedDeal(Deal referencedDeal);
    boolean existsByReferencedDealAndPipeline(Deal referencedDeal, Pipeline pipeline);
    
    // Methods that exclude deleted deals
    List<Deal> findByIsDeletedFalse();
    
    @Query("SELECT d FROM Deal d LEFT JOIN FETCH d.person LEFT JOIN FETCH d.organization WHERE d.isDeleted = false OR d.isDeleted IS NULL")
    List<Deal> findByIsDeletedFalseWithPersonAndOrganization();
    
    List<Deal> findByPipelineAndIsDeletedFalse(Pipeline pipeline);
    List<Deal> findByStageAndIsDeletedFalse(Stage stage);
    List<Deal> findByStatusAndIsDeletedFalse(DealStatus status);
    List<Deal> findByPersonAndIsDeletedFalse(Person person);
    List<Deal> findByOrganizationAndIsDeletedFalse(Organization organization);
    List<Deal> findByDealCategoryAndIsDeletedFalse(Category category);
    List<Deal> findByReferencedDealAndIsDeletedFalse(Deal referencedDeal);
    
    // Fetch deal with labels for API responses
    @EntityGraph(attributePaths = {"labels"})
    @Query("SELECT d FROM Deal d WHERE d.id = :id AND (d.isDeleted = false OR d.isDeleted IS NULL)")
    Optional<Deal> findByIdWithLabel(@Param("id") Long id);

    // Bulk detach organization from deals (including soft-deleted ones)
    @Modifying
    @Query("UPDATE Deal d SET d.organization = null WHERE d.organization.id = :organizationId")
    int clearOrganizationByOrganizationId(@Param("organizationId") Long organizationId);

    // Bulk soft-delete deals by organization (set isDeleted = true and clear organization)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Deal d SET d.isDeleted = true, d.organization = null WHERE d.organization.id = :organizationId")
    int softDeleteDealsByOrganizationId(@Param("organizationId") Long organizationId);

    /**
     * Efficiently fetch distinct person IDs for a list of deal IDs without triggering N+1 lazy loads.
     */
    @Query("select distinct d.person.id from Deal d where d.id in :dealIds and d.person is not null")
    List<Long> findDistinctPersonIdsByDealIds(@Param("dealIds") List<Long> dealIds);

    /** WON, non-deleted deals that are diverted (isDiverted=true or dealSource=DIVERT), with pipeline/refPipeline/refDeal/org/owner loaded. */
    @Query("SELECT DISTINCT d FROM Deal d " +
            "LEFT JOIN FETCH d.pipeline " +
            "LEFT JOIN FETCH d.referencedPipeline " +
            "LEFT JOIN FETCH d.referencedDeal rd " +
            "LEFT JOIN FETCH rd.pipeline " +
            "LEFT JOIN FETCH d.organization o " +
            "LEFT JOIN FETCH o.owner " +
            "WHERE d.status = :status AND (d.isDeleted = false OR d.isDeleted IS NULL) " +
            "AND (d.isDiverted = true OR d.dealSource = :divertSource)")
    List<Deal> findWonDivertedDealsForReport(@Param("status") DealStatus status, @Param("divertSource") DealSource divertSource);

    /** All diverted deals (any status), non-deleted, with pipeline/refPipeline/refDeal/org/owner loaded. */
    @Query("SELECT DISTINCT d FROM Deal d " +
            "LEFT JOIN FETCH d.pipeline " +
            "LEFT JOIN FETCH d.referencedPipeline " +
            "LEFT JOIN FETCH d.referencedDeal rd " +
            "LEFT JOIN FETCH rd.pipeline " +
            "LEFT JOIN FETCH d.organization o " +
            "LEFT JOIN FETCH o.owner " +
            "WHERE (d.isDeleted = false OR d.isDeleted IS NULL) " +
            "AND (d.isDiverted = true OR d.dealSource = :divertSource)")
    List<Deal> findAllDivertedDealsForReport(@Param("divertSource") DealSource divertSource);

    /**
     * Count diverted deals (non-deleted, isDiverted=true or dealSource=DIVERT) grouped by createdByUser,
     * ordered by count descending (most to least). Returns [userId, firstName, lastName, email, count].
     * Excludes deals with no createdByUser.
     */
    @Query("SELECT d.createdByUser.id, d.createdByUser.firstName, d.createdByUser.lastName, d.createdByUser.email, COUNT(d) " +
            "FROM Deal d INNER JOIN d.createdByUser " +
            "WHERE (d.isDeleted = false OR d.isDeleted IS NULL) " +
            "AND (d.isDiverted = true OR d.dealSource = :divertSource) " +
            "GROUP BY d.createdByUser.id, d.createdByUser.firstName, d.createdByUser.lastName, d.createdByUser.email " +
            "ORDER BY COUNT(d) DESC")
    List<Object[]> countDivertedDealsByDivertedByUser(@Param("divertSource") DealSource divertSource);

    /**
     * Count diverted deals by createdByUser and month for a given year (using createdAt).
     * Returns [userId, firstName, lastName, email, month, count] ordered by month, then count descending.
     */
    @Query("SELECT d.createdByUser.id, d.createdByUser.firstName, d.createdByUser.lastName, d.createdByUser.email, " +
            "FUNCTION('MONTH', d.createdAt), COUNT(d) " +
            "FROM Deal d INNER JOIN d.createdByUser " +
            "WHERE (d.isDeleted = false OR d.isDeleted IS NULL) " +
            "AND (d.isDiverted = true OR d.dealSource = :divertSource) " +
            "AND FUNCTION('YEAR', d.createdAt) = :year " +
            "GROUP BY d.createdByUser.id, d.createdByUser.firstName, d.createdByUser.lastName, d.createdByUser.email, FUNCTION('MONTH', d.createdAt) " +
            "ORDER BY FUNCTION('MONTH', d.createdAt), COUNT(d) DESC")
    List<Object[]> countDivertedDealsByDivertedByUserMonthly(@Param("divertSource") DealSource divertSource, @Param("year") Integer year);

    /** All non-deleted deals with specific subsource, with organization loaded. */
    @Query("SELECT DISTINCT d FROM Deal d " +
            "LEFT JOIN FETCH d.organization o " +
            "WHERE (d.isDeleted = false OR d.isDeleted IS NULL) " +
            "AND d.dealSubSource = :subSource")
    List<Deal> findByDealSubSource(@Param("subSource") DealSubSource subSource);

    /**
     * Count LOST, non-deleted deals per stage.
     * Returns [stageId, stageName, lostCount].
     */
    @Query("SELECT d.stage.id, d.stage.name, COUNT(d) " +
            "FROM Deal d " +
            "WHERE d.status = com.brideside.crm.entity.DealStatus.LOST " +
            "AND (d.isDeleted = false OR d.isDeleted IS NULL) " +
            "AND d.stage IS NOT NULL " +
            "GROUP BY d.stage.id, d.stage.name " +
            "ORDER BY d.stage.id")
    List<Object[]> countLostDealsByStage();

    /**
     * Count LOST, non-deleted deals per stage and organization category.
     * Returns [stageId, stageName, categoryEnum, lostCount].
     */
    @Query("SELECT d.stage.id, d.stage.name, o.category, COUNT(d) " +
            "FROM Deal d " +
            "LEFT JOIN d.organization o " +
            "WHERE d.status = com.brideside.crm.entity.DealStatus.LOST " +
            "AND (d.isDeleted = false OR d.isDeleted IS NULL) " +
            "AND d.stage IS NOT NULL " +
            "GROUP BY d.stage.id, d.stage.name, o.category " +
            "ORDER BY d.stage.id")
    List<Object[]> countLostDealsByStageAndCategory();

    /**
     * Live deals with the fields used to tell that the same person is in another pipeline.
     * Returns [dealId, pipelineId, pipelineName, personId, personPhone, instagramId, contactNumber, phoneNumber].
     */
    @Query(value = "SELECT d.id, d.pipeline_id, pl.name, d.person_id, p.phone, p.instagram_id, d.contact_number, d.phone_number " +
            "FROM deals d " +
            "LEFT JOIN persons p ON p.id = d.person_id " +
            "LEFT JOIN pipelines pl ON pl.id = d.pipeline_id " +
            "WHERE d.is_deleted = 0 OR d.is_deleted IS NULL", nativeQuery = true)
    List<Object[]> findLiveDealPipelineKeys();

    /**
     * Non-deleted deals in the pipeline for the same person (any deal row, not only auto-diverted).
     */
    @Query("SELECT COUNT(d) FROM Deal d WHERE d.pipeline.id = :pipelineId AND d.person.id = :personId "
            + "AND (d.isDeleted = false OR d.isDeleted IS NULL)")
    long countNonDeletedInPipelineWithPersonId(
            @Param("pipelineId") Long pipelineId,
            @Param("personId") Long personId);

    /**
     * Non-deleted deals in pipeline whose linked person has the same instagram_id (case-insensitive, trimmed).
     */
    @Query(value = "SELECT COUNT(*) FROM deals d INNER JOIN persons p ON d.person_id = p.id " +
            "WHERE d.pipeline_id = :pipelineId " +
            "AND (d.is_deleted IS NULL OR d.is_deleted = 0) " +
            "AND p.instagram_id IS NOT NULL AND TRIM(p.instagram_id) <> '' " +
            "AND LOWER(TRIM(p.instagram_id)) = LOWER(TRIM(:instagramId))", nativeQuery = true)
    long countNonDeletedInPipelineWithPersonInstagramId(
            @Param("pipelineId") Long pipelineId,
            @Param("instagramId") String instagramId);
}



