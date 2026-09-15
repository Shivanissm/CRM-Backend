package com.brideside.crm.service;

import com.brideside.crm.dto.DealDtos;
import com.brideside.crm.dto.PipelineDtos;
import com.brideside.crm.entity.Deal;
import com.brideside.crm.entity.DealStatus;

import java.util.List;

public interface DealService {
    Deal create(DealDtos.CreateRequest request);
    Deal get(Long id);
    List<Deal> list();
    List<Deal> list(String sortField, String sortDirection);
    List<Deal> list(Long pipelineId, String status, Long organizationId, Long categoryId, 
                    Long managerId, String dateFrom, String dateTo, String search, String source,
                    String sortField, String sortDirection, Integer limit, Integer offset, Long stageId,
                    Long personId);
    long count(Long pipelineId, String status, Long organizationId, Long categoryId, 
               Long managerId, String dateFrom, String dateTo, String search, String source, Long stageId,
               Long personId);
    List<com.brideside.crm.dto.PersonDTO> getPersonsByDealIds(List<Long> dealIds);
    List<com.brideside.crm.dto.ActivityDTO> getActivitiesByDealIds(List<Long> dealIds);
    List<Deal> listWon();
    List<Deal> listByStatus(DealStatus status);
    List<Deal> listByPerson(Long personId);

    /**
     * When a person receives a phone number, move their BOT-created deals from Lead In to Qualified.
     */
    void promoteBotDealsWhenPhoneReceived(Long personId);
    List<Deal> listByOrganization(Long organizationId);
    List<Deal> listByCategory(Long categoryId);
    Deal update(Long id, DealDtos.UpdateRequest request);
    Deal updateStage(Long id, DealDtos.UpdateStageRequest request);
    Deal markStatus(Long id, DealDtos.MarkStatusRequest request);
    void delete(Long id);
    List<PipelineDtos.PipelineResponse> getAvailablePipelinesForDiversion(Long dealId);
    DealDtos.StageTotalsResponse getStageTotals(Long pipelineId, String status, Long organizationId, 
                                                  Long categoryId, Long managerId, String dateFrom, 
                                                  String dateTo, String search, String source);

    /**
     * Aggregated totals of deals per sales user (organization owner), optionally filtered by pipeline.
     *
     * @param pipelineId optional pipeline id to filter deals; when null, includes all pipelines
     * @return aggregated won/lost/all deal counts and values per sales user
     */
    DealDtos.UserDealTotalsResponse getUserDealTotals(Long pipelineId);
    
    /**
     * Calculate total revenue (sum of deal values) for deals matching the specified filters.
     * This is optimized for dashboard revenue calculation - calculates SUM on backend instead of
     * fetching all deals and calculating on frontend.
     * 
     * @param pipelineId Optional pipeline filter
     * @param status Optional status filter (WON, LOST, IN_PROGRESS, or 'all')
     * @param organizationId Optional organization filter
     * @param categoryId Optional category filter
     * @param managerId Optional manager filter (person owner)
     * @param dateFrom Optional start date filter (YYYY-MM-DD)
     * @param dateTo Optional end date filter (YYYY-MM-DD)
     * @param search Optional search term
     * @param source Optional deal source filter
     * @param stageId Optional stage filter
     * @return RevenueResponse with total revenue and deal count
     */
    DealDtos.RevenueResponse calculateRevenue(Long pipelineId, String status, Long organizationId, 
                                              Long categoryId, Long managerId, String dateFrom, 
                                              String dateTo, String search, String source, Long stageId);

    /**
     * Total LOST deals per stage (non-deleted deals only).
     */
    DealDtos.LostDealsByStageResponse getLostDealsByStage();
}



