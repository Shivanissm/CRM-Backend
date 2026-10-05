package com.brideside.crm.service.impl;

import com.brideside.crm.dto.DealResponse;
import com.brideside.crm.dto.LabelDtos;
import com.brideside.crm.dto.PersonDTO;
import com.brideside.crm.dto.SearchDtos;
import com.brideside.crm.entity.Deal;
import com.brideside.crm.entity.Person;
import com.brideside.crm.entity.Role;
import com.brideside.crm.entity.User;
import com.brideside.crm.mapper.PersonMapper;
import com.brideside.crm.repository.DealRepository;
import com.brideside.crm.repository.DealSpecifications;
import com.brideside.crm.repository.PersonRepository;
import com.brideside.crm.repository.PersonSpecifications;
import com.brideside.crm.repository.UserRepository;
import com.brideside.crm.service.PipelineService;
import com.brideside.crm.service.SearchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SearchServiceImpl implements SearchService {
    
    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);
    private static final int DEFAULT_LIMIT = 10;
    
    private final PersonRepository personRepository;
    private final DealRepository dealRepository;
    private final UserRepository userRepository;
    private final PipelineService pipelineService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public SearchServiceImpl(PersonRepository personRepository,
                            DealRepository dealRepository,
                            UserRepository userRepository,
                            PipelineService pipelineService) {
        this.personRepository = personRepository;
        this.dealRepository = dealRepository;
        this.userRepository = userRepository;
        this.pipelineService = pipelineService;
    }
    
    @Override
    public SearchDtos.GlobalSearchResponse globalSearch(String query, Integer limit) {
        if (query == null || query.trim().isEmpty()) {
            return new SearchDtos.GlobalSearchResponse(List.of(), List.of());
        }
        
        int searchLimit = (limit != null && limit > 0) ? limit : DEFAULT_LIMIT;
        Pageable pageable = PageRequest.of(0, searchLimit);
        
        log.debug("Global search requested with query: '{}', limit: {}", query, searchLimit);
        
        // Persons: no role-based filter (same idea as GET /api/persons/search) — all non-deleted matches
        Specification<Person> personSpec = Specification.where(PersonSpecifications.notDeleted())
                .and(PersonSpecifications.focusedSearch(query));
        
        List<PersonDTO> persons = personRepository.findAll(personSpec, pageable)
                .stream()
                .map(PersonMapper::toDto)
                .collect(Collectors.toList());
        
        log.debug("Found {} persons matching query '{}' (unrestricted by role)", persons.size(), query);
        
        Specification<Deal> dealSpec = Specification.where(DealSpecifications.notDeleted())
                .and(DealSpecifications.focusedSearch(query));

        Optional<User> currentUserOpt = getCurrentUser();
        Role.RoleName roleName = (currentUserOpt.isPresent() && currentUserOpt.get().getRole() != null)
                ? currentUserOpt.get().getRole().getName()
                : null;

        if (roleName == Role.RoleName.SALES || roleName == Role.RoleName.PRESALES) {
            Set<Long> visiblePipelineIds = pipelineService.getVisiblePipelineIds();
            dealSpec = dealSpec.and(DealSpecifications.hasPipelineIn(visiblePipelineIds));
            log.debug("Deal search restricted to team pipelines: {}", visiblePipelineIds);
        } else {
            List<Long> accessibleOwnerIds = getAccessibleOwnerIds();
            log.debug("Deal RBAC: accessible owner IDs: {}", accessibleOwnerIds);
            if (accessibleOwnerIds != null) {
                dealSpec = dealSpec.and(DealSpecifications.hasOrganizationOrPersonOwnerIn(accessibleOwnerIds));
            }
        }
        
        List<Deal> dealEntities = dealRepository.findAll(dealSpec, pageable).getContent();
        
        // Convert deals to DealResponse using DealService's toResponse method
        // We need to use DealController's toResponse method, but since it's private,
        // we'll create a simpler conversion here or use DealService
        List<DealResponse> deals = dealEntities.stream()
                .map(this::toDealResponse)
                .collect(Collectors.toList());
        
        log.debug("Found {} deals matching query '{}' (after deal RBAC)", deals.size(), query);
        
        return new SearchDtos.GlobalSearchResponse(persons, deals);
    }
    
    /**
     * Owner IDs used for deal RBAC in global search only (persons are unrestricted).
     * Returns null for Admin (no restrictions), or list of owner IDs for other roles.
     * Logic matches OrganizationServiceImpl.listAccessibleForCurrentUser:
     * - ADMIN: null (no restrictions)
     * - CATEGORY_MANAGER: themselves + Sales/Presales under them
     * - SALES: themselves + Presales under them
     * - PRESALES: themselves only
     */
    private List<Long> getAccessibleOwnerIds() {
        Optional<User> currentUserOpt = getCurrentUser();
        
        // If no user is logged in, return empty list (no access)
        if (currentUserOpt.isEmpty() || currentUserOpt.get().getRole() == null) {
            log.warn("No authenticated user found for global search - returning empty results");
            return List.of();
        }
        
        User currentUser = currentUserOpt.get();
        Role.RoleName roleName = currentUser.getRole().getName();
        
        // Admin sees all (no filtering)
        if (roleName == Role.RoleName.ADMIN) {
            return null; // null means no restrictions
        }
        
        List<Long> ownerIds = new ArrayList<>();
        
        switch (roleName) {
            case CATEGORY_MANAGER:
                ownerIds = findAccessibleOwnerIdsForCategoryManager(currentUser);
                break;
            case SALES:
                ownerIds = findAccessibleOwnerIdsForSales(currentUser);
                break;
            case PRESALES:
                // Presales sees the same data as Sales - if they have a Sales manager, see what that manager sees
                // This includes: Sales manager's organizations + all Presales under that Sales manager (including themselves)
                if (currentUser.getManager() != null && 
                    currentUser.getManager().getRole() != null &&
                    currentUser.getManager().getRole().getName() == Role.RoleName.SALES) {
                    // Use the same logic as Sales - see what their Sales manager sees
                    ownerIds = findAccessibleOwnerIdsForSales(currentUser.getManager());
                    // Ensure the Presales user's own ID is included (in case they're not in the manager's list)
                    if (!ownerIds.contains(currentUser.getId())) {
                        ownerIds = new ArrayList<>(ownerIds);
                        ownerIds.add(currentUser.getId());
                    }
                } else {
                    // No Sales manager - see only their own organizations
                    ownerIds = List.of(currentUser.getId());
                }
                break;
            default:
                // Unknown role - no access
                ownerIds = List.of();
        }
        
        return ownerIds;
    }
    
    /**
     * Find all user IDs that a Category Manager can access (for organization ownership)
     * Includes: the category manager themselves, direct Sales/Presales reports, and Sales who have Presales under them
     */
    private List<Long> findAccessibleOwnerIdsForCategoryManager(User categoryManager) {
        List<Long> ownerIds = new ArrayList<>();
        ownerIds.add(categoryManager.getId()); // Category Manager's own organizations
        
        // Find direct reports (Sales and Presales directly under this Category Manager)
        List<User> directReports = userRepository.findByManagerId(categoryManager.getId());
        for (User report : directReports) {
            if (report.getRole() != null) {
                Role.RoleName reportRole = report.getRole().getName();
                if (reportRole == Role.RoleName.SALES || reportRole == Role.RoleName.PRESALES) {
                    ownerIds.add(report.getId());
                }
            }
        }
        
        // Find Sales under this Category Manager, then find their Presales
        for (User sales : directReports) {
            if (sales.getRole() != null && sales.getRole().getName() == Role.RoleName.SALES) {
                List<User> presalesUnderSales = userRepository.findByManagerId(sales.getId());
                for (User presales : presalesUnderSales) {
                    if (presales.getRole() != null && presales.getRole().getName() == Role.RoleName.PRESALES) {
                        ownerIds.add(presales.getId());
                    }
                }
            }
        }
        
        return ownerIds;
    }
    
    /**
     * Find all user IDs that a Sales user can access (for organization ownership)
     * Includes: the sales user themselves and their direct Presales reports
     */
    private List<Long> findAccessibleOwnerIdsForSales(User salesUser) {
        List<Long> ownerIds = new ArrayList<>();
        ownerIds.add(salesUser.getId()); // Sales user's own organizations
        
        // Find direct Presales reports under this Sales user
        List<User> directReports = userRepository.findByManagerId(salesUser.getId());
        for (User report : directReports) {
            if (report.getRole() != null && report.getRole().getName() == Role.RoleName.PRESALES) {
                ownerIds.add(report.getId());
            }
        }
        
        return ownerIds;
    }
    
    /**
     * Get the current authenticated user
     */
    private Optional<User> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetails)) {
            return Optional.empty();
        }
        String email = ((UserDetails) authentication.getPrincipal()).getUsername();
        return userRepository.findByEmail(email);
    }
    
    /**
     * Convert Deal entity to DealResponse
     * Similar to DealController.toResponse but adapted for search results
     */
    private DealResponse toDealResponse(Deal deal) {
        DealResponse response = new DealResponse();
        response.id = deal.getId();
        response.name = deal.getName();
        response.value = deal.getValue();
        response.personId = deal.getPersonId();
        response.personName = deal.getPersonName();
        response.pipelineId = deal.getPipeline() != null ? deal.getPipeline().getId() : null;
        response.stageId = deal.getStage() != null ? deal.getStage().getId() : null;
        response.sourceId = deal.getSource() != null ? deal.getSource().getId() : null;
        response.organizationId = deal.getOrganization() != null ? deal.getOrganization().getId() : null;
        response.organizationName = deal.getOrganization() != null ? deal.getOrganization().getName() : null;
        response.categoryId = deal.getDealCategory() != null ? deal.getDealCategory().getId() : null;
        response.eventType = deal.getEventType();
        response.status = deal.getStatus();
        response.commissionAmount = deal.getCommissionAmount();
        response.createdAt = deal.getCreatedAt();
        response.updatedAt = deal.getUpdatedAt();
        response.venue = deal.getVenue();
        response.phoneNumber = deal.getPhoneNumber();
        response.city = deal.getCity();
        response.finalThankYouSent = deal.getFinalThankYouSent();
        response.eventDateAsked = deal.getEventDateAsked();
        response.contactNumberAsked = deal.getContactNumberAsked();
        response.venueAsked = deal.getVenueAsked();
        response.eventDate = deal.getEventDate() != null ? deal.getEventDate().toString() : null;
        response.eventDates = parseEventDates(deal);
        response.isDeleted = deal.getIsDeleted();
        response.createdBy = deal.getCreatedBy();
        response.createdByUserId = deal.getCreatedByUserId();
        response.createdByName = deal.getCreatedByName();
        if (deal.getOwner() != null) {
            response.ownerId = deal.getOwner().getId();
            response.ownerDisplayName = deal.getOwner().getDisplayName();
        } else if (deal.getOwnerId() != null) {
            response.ownerId = deal.getOwnerId();
            response.ownerDisplayName = null;
        }
        response.isDiverted = deal.getIsDiverted();
        response.referencedDealId = deal.getReferencedDeal() != null ? deal.getReferencedDeal().getId() : null;
        response.referencedPipelineId = deal.getReferencedPipeline() != null ? deal.getReferencedPipeline().getId() : null;
        response.sourcePipelineId = deal.getSourcePipeline() != null ? deal.getSourcePipeline().getId() : null;
        response.pipelineHistory = deal.getPipelineHistory();
        response.lostReason = deal.getLostReason() != null ? deal.getLostReason().toDisplayString() : null;
        response.clientBudget = deal.getClientBudget();
        
        // Handle source
        if (deal.getDealSource() != null) {
            response.source = deal.getDealSource().toDisplayString();
        }
        if (deal.getDealSubSource() != null) {
            response.subSource = deal.getDealSubSource().toDisplayString();
        }
        
        // Handle labels
        Set<com.brideside.crm.entity.Label> dealLabels = deal.getLabels();
        if (dealLabels != null && !dealLabels.isEmpty()) {
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
            response.labelIds = labelIdList;
            response.labels = labelList;
            if (!labelIdList.isEmpty()) {
                response.labelId = labelIdList.get(0);
                response.label = labelList.get(0);
            } else {
                response.labelId = null;
                response.label = null;
            }
            response.labelString = null;
        } else if (deal.getLabelEnum() != null) {
            response.labelString = deal.getLabelEnum().toDisplayString();
            response.labelId = null;
            response.label = null;
            response.labelIds = null;
            response.labels = null;
        } else {
            response.labelString = null;
            response.labelId = null;
            response.label = null;
            response.labelIds = null;
            response.labels = null;
        }
        
        return response;
    }
    
    /**
     * Parses eventDates JSON string to a list of date strings.
     */
    private List<String> parseEventDates(Deal deal) {
        if (deal.getEventDates() == null || deal.getEventDates().isEmpty()) {
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
            if (deal.getEventDate() != null) {
                return List.of(deal.getEventDate().toString());
            }
            return null;
        }
    }
}

