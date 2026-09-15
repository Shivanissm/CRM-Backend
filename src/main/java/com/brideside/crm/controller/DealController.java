package com.brideside.crm.controller;

import com.brideside.crm.dto.ActivityDTO;
import com.brideside.crm.dto.ApiResponse;
import com.brideside.crm.dto.DealDtos;
import com.brideside.crm.dto.DealResponse;
import com.brideside.crm.dto.LabelDtos;
import com.brideside.crm.dto.PersonDTO;
import com.brideside.crm.entity.Deal;
import com.brideside.crm.entity.DealStatus;
import com.brideside.crm.service.DealService;
import com.brideside.crm.service.DealStageHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/deals")
@Tag(name = "Deals", description = "Deal management APIs")
public class DealController {

    @Autowired
    private DealService dealService;
    
    @Autowired
    private DealStageHistoryService dealStageHistoryService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping
    @Operation(
            summary = "Create a deal",
            description = "Creates a new deal. Use GET /api/venues/autocomplete?input=<query> to search and select venues for the venue field."
    )
    public ResponseEntity<DealResponse> create(@Valid @RequestBody DealDtos.CreateRequest req) {
        Deal d = dealService.create(req);
        return ResponseEntity.ok(toResponse(d));
    }

    @GetMapping
    @Operation(summary = "List deals", description = "List deals with optional filters, sorting, and pagination. " +
            "Filters: pipelineId, personId, status (IN_PROGRESS/WON/LOST/all), organizationId, categoryId, managerId, stageId, dateFrom (YYYY-MM-DD), dateTo (YYYY-MM-DD), search (name/venue/person/organization), source (Direct/Divert/Reference/Planner/TBS). " +
            "Sort: 'field,direction' (e.g., 'name,asc' or 'value,desc'). Default: 'nextActivity,asc'. " +
            "Pagination: limit (default: 100), offset (default: 0). " +
            "Response: by default returns a JSON array of deals (legacy). Pass wrapped=true for {deals, totalCount, persons, activities}. " +
            "X-Total-Count header is always set.")
    public ResponseEntity<?> list(
            @RequestParam(required = false) Long pipelineId,
            @RequestParam(required = false) Long personId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long managerId,
            @RequestParam(required = false) Long stageId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "nextActivity,asc") String sort,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false, defaultValue = "false") boolean wrapped) {
        
        // Validate numeric parameters
        if (pipelineId != null && pipelineId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (personId != null && personId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (organizationId != null && organizationId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (categoryId != null && categoryId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (managerId != null && managerId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (stageId != null && stageId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        
        // Validate pagination parameters
        if (limit != null && limit <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (offset != null && offset < 0) {
            return ResponseEntity.badRequest().build();
        }
        
        // Validate date formats
        if (dateFrom != null && !dateFrom.trim().isEmpty()) {
            try {
                java.time.LocalDate.parse(dateFrom.trim());
            } catch (Exception e) {
                return ResponseEntity.badRequest().build();
            }
        }
        if (dateTo != null && !dateTo.trim().isEmpty()) {
            try {
                java.time.LocalDate.parse(dateTo.trim());
            } catch (Exception e) {
                return ResponseEntity.badRequest().build();
            }
        }
        
        // Parse sort parameter
        String[] sortParts = sort.split(",");
        String sortField = sortParts.length > 0 ? sortParts[0].trim() : "nextActivity";
        String sortDirection = sortParts.length > 1 ? sortParts[1].trim() : "asc";
        
        // Validate sort direction
        if (!sortDirection.equalsIgnoreCase("asc") && !sortDirection.equalsIgnoreCase("desc")) {
            sortDirection = "asc"; // Default to asc if invalid
        }
        
        // Get deals list with pagination
        List<Deal> dealEntities = dealService.list(
                pipelineId, status, organizationId, categoryId, managerId,
                dateFrom, dateTo, search, source, sortField, sortDirection, limit, offset, stageId, personId
        );
        
        List<DealResponse> deals = dealEntities.stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
        
        // Get total count with same filters (before pagination)
        long totalCount = dealService.count(
                pipelineId, status, organizationId, categoryId, managerId,
                dateFrom, dateTo, search, source, stageId, personId
        );
        
        if (wrapped) {
            List<Long> dealIds = dealEntities.stream()
                .map(Deal::getId)
                .collect(Collectors.toList());
            List<PersonDTO> persons = dealService.getPersonsByDealIds(dealIds);
            List<ActivityDTO> activities = dealService.getActivitiesByDealIds(dealIds);
            DealDtos.ListResponse response = new DealDtos.ListResponse(deals, totalCount, persons, activities);
            return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(totalCount))
                .body(response);
        }

        // Legacy/default: return plain array — existing frontend expects DealResponse[]
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(totalCount))
            .body(deals);
    }

    @GetMapping("/won")
    @Operation(summary = "List won deals")
    public ResponseEntity<List<DealResponse>> listWon() {
        List<DealResponse> res = dealService.listWon().stream().map(this::toResponse).collect(Collectors.toList());
        return ResponseEntity.ok(res);
    }

    @GetMapping("/lost")
    @Operation(summary = "List lost deals")
    public ResponseEntity<List<DealResponse>> listLost() {
        List<DealResponse> res = dealService.listByStatus(DealStatus.LOST).stream().map(this::toResponse).collect(Collectors.toList());
        return ResponseEntity.ok(res);
    }

    @GetMapping("/inprogress")
    @Operation(summary = "List in-progress deals")
    public ResponseEntity<List<DealResponse>> listInProgress() {
        List<DealResponse> res = dealService.listByStatus(DealStatus.IN_PROGRESS).stream().map(this::toResponse).collect(Collectors.toList());
        return ResponseEntity.ok(res);
    }

    @GetMapping("/person/{personId}")
    @Operation(summary = "List deals for a person")
    public ResponseEntity<List<DealResponse>> listByPerson(@PathVariable Long personId) {
        List<DealResponse> res = dealService.listByPerson(personId).stream().map(this::toResponse).collect(Collectors.toList());
        return ResponseEntity.ok(res);
    }

    @GetMapping("/organization/{organizationId}")
    @Operation(summary = "List deals for an organization")
    public ResponseEntity<List<DealResponse>> listByOrganization(@PathVariable Long organizationId) {
        List<DealResponse> res = dealService.listByOrganization(organizationId).stream().map(this::toResponse).collect(Collectors.toList());
        return ResponseEntity.ok(res);
    }

    @GetMapping("/category/{categoryId}")
    @Operation(summary = "List deals for a category")
    public ResponseEntity<List<DealResponse>> listByCategory(@PathVariable Long categoryId) {
        List<DealResponse> res = dealService.listByCategory(categoryId).stream().map(this::toResponse).collect(Collectors.toList());
        return ResponseEntity.ok(res);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get deal by id", description = "Returns deal details along with related persons and activities for the specified deal")
    public ResponseEntity<DealDtos.DetailResponse> get(@PathVariable Long id) {
        Deal deal = dealService.get(id);
        DealResponse dealResponse = toResponse(deal);
        
        // Fetch persons and activities for this specific deal using JOINs
        List<Long> dealIds = List.of(id);
        List<PersonDTO> persons = dealService.getPersonsByDealIds(dealIds);
        List<ActivityDTO> activities = dealService.getActivitiesByDealIds(dealIds);
        
        DealDtos.DetailResponse response = new DealDtos.DetailResponse(dealResponse, persons, activities);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update deal details", description = "Partially update deal fields. Only provided fields will be updated.")
    public ResponseEntity<DealResponse> update(@PathVariable Long id, @Valid @RequestBody DealDtos.UpdateRequest req) {
        Deal d = dealService.update(id, req);
        return ResponseEntity.ok(toResponse(d));
    }

    @PutMapping("/{id}/stage")
    @Operation(summary = "Move deal to another stage")
    public ResponseEntity<DealResponse> updateStage(@PathVariable Long id, @RequestBody DealDtos.UpdateStageRequest req) {
        return ResponseEntity.ok(toResponse(dealService.updateStage(id, req)));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update deal status", description = "Update deal status. When marking as LOST, lostReason is required.")
    public ResponseEntity<DealResponse> markStatus(@PathVariable Long id, @Valid @RequestBody DealDtos.MarkStatusRequest req) {
        return ResponseEntity.ok(toResponse(dealService.markStatus(id, req)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete deal")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        dealService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private DealResponse toResponse(Deal d) {
        DealResponse r = new DealResponse();
        r.id = d.getId();
        r.name = d.getName();
        r.value = d.getValue();
        r.personId = d.getPersonId();
        r.personName = d.getPersonName();
        r.pipelineId = d.getPipeline() != null ? d.getPipeline().getId() : null;
        r.stageId = d.getStage() != null ? d.getStage().getId() : null;
        r.sourceId = d.getSource() != null ? d.getSource().getId() : null;
        r.organizationId = d.getOrganization() != null ? d.getOrganization().getId() : null;
        r.organizationName = d.getOrganization() != null ? d.getOrganization().getName() : null;
        r.categoryId = d.getDealCategory() != null ? d.getDealCategory().getId() : null;
        r.eventType = d.getEventType();
        r.status = d.getStatus();
        r.commissionAmount = d.getCommissionAmount();
        r.createdAt = d.getCreatedAt();
        r.updatedAt = d.getUpdatedAt();
        r.venue = d.getVenue();
        r.phoneNumber = d.getPhoneNumber();
        r.city = d.getCity();
        r.notes = d.getNotes();
        r.finalThankYouSent = d.getFinalThankYouSent();
        r.eventDateAsked = d.getEventDateAsked();
        r.contactNumberAsked = d.getContactNumberAsked();
        r.venueAsked = d.getVenueAsked();
        r.eventDate = d.getEventDate() != null ? d.getEventDate().toString() : null; // Legacy field
        r.eventDates = parseEventDates(d); // New field for multiple dates
        
        // Handle labels: support multiple labels from labels table
        // Also maintain backward compatibility with single label and legacy enum
        Set<com.brideside.crm.entity.Label> dealLabels = d.getLabels();
        if (dealLabels != null && !dealLabels.isEmpty()) {
            // Multiple custom labels from labels table - trigger lazy load by accessing properties
            List<Long> labelIdList = new ArrayList<>();
            List<LabelDtos.Response> labelList = new ArrayList<>();
            for (com.brideside.crm.entity.Label dealLabel : dealLabels) {
                dealLabel.getId(); // Trigger lazy load
                labelIdList.add(dealLabel.getId());
                labelList.add(new LabelDtos.Response(
                    dealLabel.getId(),
                    dealLabel.getName(),
                    dealLabel.getColor(),
                    dealLabel.getCreatedAt(),
                    dealLabel.getUpdatedAt()
                ));
            }
            r.labelIds = labelIdList;
            r.labels = labelList;
            // Backward compatibility: set first label as single label
            if (!labelIdList.isEmpty()) {
                r.labelId = labelIdList.get(0);
                r.label = labelList.get(0);
            } else {
                r.labelId = null;
                r.label = null;
            }
            r.labelString = null; // Set legacy string to null when using custom labels
        } else if (d.getLabelEnum() != null) {
            // Legacy enum label - set as string for backward compatibility
            r.labelString = d.getLabelEnum().toDisplayString();
            r.labelId = null;
            r.label = null;
            r.labelIds = null;
            r.labels = null;
        } else {
            r.labelString = null;
            r.labelId = null;
            r.label = null;
            r.labelIds = null;
            r.labels = null;
        }
        r.source = d.getDealSource() != null ? d.getDealSource().toDisplayString() : null;
        r.subSource = d.getDealSubSource() != null ? d.getDealSubSource().toDisplayString() : null;
        r.isDiverted = d.getIsDiverted();
        r.referencedDealId = d.getReferencedDeal() != null ? d.getReferencedDeal().getId() : null;
        r.referencedPipelineId = d.getReferencedPipeline() != null ? d.getReferencedPipeline().getId() : null;
        r.sourcePipelineId = d.getSourcePipeline() != null ? d.getSourcePipeline().getId() : null;
        r.pipelineHistory = d.getPipelineHistory();
        r.isDeleted = d.getIsDeleted();
        r.lostReason = d.getLostReason() != null ? d.getLostReason().toDisplayString() : null;
        r.clientBudget = d.getClientBudget();
        r.wonAt = d.getWonAt();
        r.lostAt = d.getLostAt();
        r.createdBy = d.getCreatedBy();
        r.createdByUserId = d.getCreatedByUserId();
        r.createdByName = d.getCreatedByName();
        if (d.getOwner() != null) {
            r.ownerId = d.getOwner().getId();
            r.ownerDisplayName = d.getOwner().getDisplayName();
        } else if (d.getOwnerId() != null) {
            r.ownerId = d.getOwnerId();
            r.ownerDisplayName = null;
        }
        return r;
    }

    @GetMapping("/{dealId}/available-pipelines")
    @Operation(summary = "Get available pipelines for diversion", description = "Returns pipelines where the deal has not been diverted yet")
    public ResponseEntity<List<com.brideside.crm.dto.PipelineDtos.PipelineResponse>> getAvailablePipelinesForDiversion(@PathVariable Long dealId) {
        List<com.brideside.crm.dto.PipelineDtos.PipelineResponse> pipelines = dealService.getAvailablePipelinesForDiversion(dealId);
        return ResponseEntity.ok(pipelines);
    }

    @GetMapping("/{dealId}/stage-durations")
    @Operation(summary = "Get stage durations for a deal", description = "Returns a map of stageId -> days for each stage the deal has been in")
    public ResponseEntity<Map<Long, Integer>> getStageDurations(@PathVariable Long dealId) {
        Map<Long, Integer> durations = dealStageHistoryService.getAllStageDurations(dealId);
        return ResponseEntity.ok(durations);
    }

    @GetMapping("/{dealId}/current-stage-duration")
    @Operation(summary = "Get current stage duration for a deal", description = "Returns the number of days the deal has been in its current stage")
    public ResponseEntity<Integer> getCurrentStageDuration(@PathVariable Long dealId) {
        int days = dealStageHistoryService.getDaysInCurrentStage(dealId);
        return ResponseEntity.ok(days);
    }

    @GetMapping("/average-deal-timeline")
    @Operation(summary = "Get average deal timeline per month", description = "Returns the average time (in days) deals spend in each stage, grouped by month and stage name. Each month is the year-month when deals exited that stage (yyyy-MM). Only completed stage visits are included.")
    public ResponseEntity<DealDtos.AverageDealTimelinePerMonthResponse> getAverageDealTimeline() {
        return ResponseEntity.ok(dealStageHistoryService.getAverageDealTimelinePerMonth());
    }

    @GetMapping("/average-deal-timeline-diverted")
    @Operation(summary = "Get average diverted deal timeline", description = "Returns the average time (in days) diverted deals spend in each stage, grouped by stage name. Only completed stage visits are included (deals that have left the stage). A diverted deal is identified via deal_source = 'DIVERT'.")
    public ResponseEntity<DealDtos.AverageDealTimelineResponse> getAverageDealTimelineForDivertedDeals() {
        return ResponseEntity.ok(dealStageHistoryService.getAverageDealTimelineForDivertedDeals());
    }
    
    /**
     * Parses eventDates JSON string to a list of date strings.
     */
    private List<String> parseEventDates(Deal deal) {
        if (deal.getEventDates() == null || deal.getEventDates().isEmpty()) {
            // Fallback to legacy eventDate if eventDates is not set
            if (deal.getEventDate() != null) {
                return List.of(deal.getEventDate().toString());
            }
            return null;
        }
        try {
            return objectMapper.readValue(
                deal.getEventDates(),
                new TypeReference<List<String>>() {}
            );
        } catch (Exception e) {
            // If parsing fails, fallback to legacy eventDate
            if (deal.getEventDate() != null) {
                return List.of(deal.getEventDate().toString());
            }
            return null;
        }
    }

    @GetMapping("/sources")
    @Operation(summary = "Get deal sources", description = "Returns list of available deal sources")
    public ResponseEntity<ApiResponse<List<SourceOption>>> getSources() {
        List<SourceOption> sources = Arrays.asList(
            new SourceOption("Direct", "Direct"),
            new SourceOption("Divert", "Divert"),
            new SourceOption("Reference", "Reference"),
            new SourceOption("Planner", "Planner"),
            new SourceOption("TBS", "TBS")
        );
        return ResponseEntity.ok(ApiResponse.success("Sources retrieved successfully", sources));
    }

    @GetMapping("/sub-sources")
    @Operation(summary = "Get deal sub sources", description = "Returns list of available deal sub sources (for Direct source)")
    public ResponseEntity<ApiResponse<List<SourceOption>>> getSubSources() {
        List<SourceOption> subSources = Arrays.asList(
            new SourceOption("Instagram", "Instagram"),
            new SourceOption("Whatsapp", "Whatsapp"),
            new SourceOption("Landing Page", "Landing Page"),
            new SourceOption("Email", "Email")
        );
        return ResponseEntity.ok(ApiResponse.success("Sub sources retrieved successfully", subSources));
    }

    @GetMapping("/stage-totals")
    @Operation(summary = "Get stage totals", description = "Returns aggregated stage totals (deal count and total value) for a pipeline with optional filters. " +
            "Filters: pipelineId (required), status (IN_PROGRESS/WON/LOST/all), organizationId, categoryId, managerId, dateFrom (YYYY-MM-DD), dateTo (YYYY-MM-DD), search (name/venue/person/organization), source (Direct/Divert/Reference/Planner/TBS). " +
            "Returns totals for all stages in the pipeline, even if count is 0, plus unassigned deals totals.")
    public ResponseEntity<DealDtos.StageTotalsResponse> getStageTotals(
            @RequestParam(required = true) Long pipelineId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long managerId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String source) {
        
        // Validate numeric parameters
        if (pipelineId == null || pipelineId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (organizationId != null && organizationId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (categoryId != null && categoryId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (managerId != null && managerId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        
        // Validate date formats
        if (dateFrom != null && !dateFrom.trim().isEmpty()) {
            try {
                java.time.LocalDate.parse(dateFrom.trim());
            } catch (Exception e) {
                return ResponseEntity.badRequest().build();
            }
        }
        if (dateTo != null && !dateTo.trim().isEmpty()) {
            try {
                java.time.LocalDate.parse(dateTo.trim());
            } catch (Exception e) {
                return ResponseEntity.badRequest().build();
            }
        }
        
        DealDtos.StageTotalsResponse response = dealService.getStageTotals(
                pipelineId, status, organizationId, categoryId, managerId,
                dateFrom, dateTo, search, source
        );
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/revenue")
    @Operation(summary = "Calculate revenue", description = "Calculate total revenue (sum of deal values) for deals matching the specified filters. " +
            "This endpoint is optimized for dashboard revenue calculation - calculates SUM on backend instead of fetching all deals. " +
            "Filters: pipelineId, status (IN_PROGRESS/WON/LOST/all), organizationId, categoryId, managerId, dateFrom (YYYY-MM-DD), dateTo (YYYY-MM-DD), search (name/venue/person/organization), source (Direct/Divert/Reference/Planner/TBS), stageId. " +
            "Returns total revenue and deal count.")
    public ResponseEntity<ApiResponse<DealDtos.RevenueResponse>> calculateRevenue(
            @RequestParam(required = false) Long pipelineId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long managerId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long stageId) {
        
        // Validate numeric parameters
        if (pipelineId != null && pipelineId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (organizationId != null && organizationId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (categoryId != null && categoryId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (managerId != null && managerId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (stageId != null && stageId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        
        // Validate date formats
        if (dateFrom != null && !dateFrom.trim().isEmpty()) {
            try {
                java.time.LocalDate.parse(dateFrom.trim());
            } catch (Exception e) {
                return ResponseEntity.badRequest().build();
            }
        }
        if (dateTo != null && !dateTo.trim().isEmpty()) {
            try {
                java.time.LocalDate.parse(dateTo.trim());
            } catch (Exception e) {
                return ResponseEntity.badRequest().build();
            }
        }
        
        DealDtos.RevenueResponse response = dealService.calculateRevenue(
                pipelineId, status, organizationId, categoryId, managerId, dateFrom, dateTo, search, source, stageId);
        return ResponseEntity.ok(ApiResponse.success("Revenue calculated", response));
    }

    @GetMapping("/lost-by-stage")
    @Operation(summary = "Get lost deals count per stage", description = "Returns total number of LOST, non-deleted deals in each stage.")
    public ResponseEntity<DealDtos.LostDealsByStageResponse> getLostDealsByStage() {
        return ResponseEntity.ok(dealService.getLostDealsByStage());
    }

    public static class SourceOption {
        private String code;
        private String label;

        public SourceOption(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }
}


