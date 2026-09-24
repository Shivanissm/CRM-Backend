package com.brideside.crm.service.impl;

import com.brideside.crm.dto.DealDtos;
import com.brideside.crm.dto.PipelineDtos;
import com.brideside.crm.entity.Category;
import com.brideside.crm.entity.Deal;
import com.brideside.crm.entity.DealLabel;
import com.brideside.crm.entity.DealLostReason;
import com.brideside.crm.entity.DealSource;
import com.brideside.crm.entity.DealSubSource;
import com.brideside.crm.entity.Activity;
import com.brideside.crm.entity.CreatedByType;
import com.brideside.crm.entity.DealStatus;
import com.brideside.crm.entity.Organization;
import com.brideside.crm.entity.Person;
import com.brideside.crm.entity.Pipeline;
import com.brideside.crm.entity.Source;
import com.brideside.crm.entity.Stage;
import com.brideside.crm.entity.User;
import com.brideside.crm.entity.Role;
import com.brideside.crm.mapper.PipelineMapper;
import com.brideside.crm.exception.BadRequestException;
import com.brideside.crm.exception.ResourceNotFoundException;
import com.brideside.crm.integration.calendar.GoogleCalendarService;
import com.brideside.crm.repository.ActivityRepository;
import com.brideside.crm.repository.CategoryRepository;
import com.brideside.crm.repository.DealRepository;
import com.brideside.crm.repository.DealSpecifications;
import com.brideside.crm.repository.LabelRepository;
import com.brideside.crm.repository.OrganizationRepository;
import com.brideside.crm.repository.PersonRepository;
import com.brideside.crm.repository.PipelineRepository;
import com.brideside.crm.repository.SourceRepository;
import com.brideside.crm.repository.StageRepository;
import com.brideside.crm.repository.UserRepository;
import org.springframework.data.jpa.domain.Specification;
import com.brideside.crm.service.DealService;
import com.brideside.crm.service.DealStageHistoryService;
import com.brideside.crm.service.LabelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DealServiceImpl implements DealService {

    private static final Logger log = LoggerFactory.getLogger(DealServiceImpl.class);

    @Autowired private DealRepository dealRepository;
    @Autowired private PersonRepository personRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private StageRepository stageRepository;
    @Autowired private SourceRepository sourceRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ActivityRepository activityRepository;
    
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired(required = false)
    private GoogleCalendarService googleCalendarService;
    @Autowired
    private DealStageHistoryService dealStageHistoryService;
    @Autowired
    private LabelService labelService;
    @Autowired
    private LabelRepository labelRepository;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.tbs.brideside-pipeline-id:67}")
    private Long tbsBridesidePipelineId;

    @Value("${app.tbs.brideside-organization-id:68}")
    private Long tbsBridesideOrganizationId;

    /** FK for deals.category_id on Brideside auto-diverted copies (default 4). */
    @Value("${app.tbs.brideside-deal-category-id:4}")
    private Long tbsBridesideDealCategoryId;

    @Override
    public Deal create(DealDtos.CreateRequest request) {
        Deal deal = new Deal();
        deal.setName(request.name);
        
        // Don't save deal value when creating a diverted deal
        boolean isDivertedDeal = request.label != null && 
            DealLabel.fromString(request.label) == DealLabel.DIVERT;
        
        if (isDivertedDeal) {
            deal.setValue(BigDecimal.ZERO); // Set to zero for diverted deals
        } else {
        deal.setValue(request.value != null ? request.value : BigDecimal.ZERO);
        }
        if (request.personId != null) {
            Person person = personRepository.findById(request.personId)
                .orElseThrow(() -> new ResourceNotFoundException("Person not found"));
            // Check if person is soft-deleted
            if (Boolean.TRUE.equals(person.getIsDeleted())) {
                throw new ResourceNotFoundException("Person not found");
            }
            deal.setPerson(person);
            String phone = person.getPhone();
            if (phone != null) {
                deal.setContactNumber(phone);
            }
        }
        if (request.pipelineId != null) {
            Pipeline pipeline = pipelineRepository.findById(request.pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline not found"));
            deal.setPipeline(pipeline);
        }
        if (request.stageId != null) {
            Stage stage = stageRepository.findById(request.stageId)
                .orElseThrow(() -> new ResourceNotFoundException("Stage not found"));
            deal.setStage(stage);
        }
        if (request.sourceId != null) {
            Source source = sourceRepository.findById(request.sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Source not found"));
            deal.setSource(source);
            deal.setCommissionAmount(calculateCommission(deal.getValue(), source));
        }
        if (request.organizationId != null) {
            Organization organization = organizationRepository.findById(request.organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
            deal.setOrganization(organization);
        }
        if (request.ownerId != null) {
            User owner = userRepository.findById(request.ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found"));
            // Explicit override for the deal row only; does not update person ownership.
            deal.setOwner(owner);
        }
        Category selectedCategory = resolveSelectedCategory(request);
        if (selectedCategory != null) {
            deal.setDealCategory(selectedCategory);
        }
        deal.setEventType(request.eventType);
        if (request.status != null) {
            deal.setStatus(request.status);
        } else {
            deal.setStatus(DealStatus.IN_PROGRESS);
        }
        // Keep legacy 'won' column in sync for DBs that still require it
        deal.setLegacyWon(deal.getStatus() == DealStatus.WON);
        if (deal.getStatus() == DealStatus.WON) {
            deal.setContractShared(Boolean.TRUE);
            deal.setWhatsappGroupCreated(Boolean.TRUE);
            deal.setWonAt(LocalDateTime.now());
            deal.setLostAt(null);
        } else if (deal.getStatus() == DealStatus.LOST) {
            deal.setContractShared(null);
            deal.setWhatsappGroupCreated(null);
            deal.setLostAt(LocalDateTime.now());
            deal.setWonAt(null);
        } else {
            deal.setContractShared(null);
            deal.setWhatsappGroupCreated(null);
            deal.setWonAt(null);
            deal.setLostAt(null);
        }
        if (request.commissionAmount != null) {
            deal.setCommissionAmount(request.commissionAmount);
        }
        if (deal.getContactNumber() == null) {
            deal.setContactNumber("");
        }
        if (deal.getUserName() == null) {
            deal.setUserName("");
        }
        if (deal.getCreatedAt() == null) {
            deal.setCreatedAt(LocalDateTime.now());
        }
        // Optional new fields
        deal.setVenue(request.venue);
        // Update the linked person's venue if the deal has a person and venue is provided
        if (request.venue != null && deal.getPerson() != null) {
            Person person = deal.getPerson();
            person.setVenue(request.venue);
            personRepository.save(person);
        }
        deal.setPhoneNumber(request.phoneNumber);
        deal.setCity(request.city);
        deal.setNotes(request.notes);
        deal.setFinalThankYouSent(request.finalThankYouSent);
        deal.setEventDateAsked(request.eventDateAsked);
        deal.setContactNumberAsked(request.contactNumberAsked);
        deal.setVenueAsked(request.venueAsked);
        
        // Handle multiple event dates (preferred)
        if (request.eventDates != null && !request.eventDates.isEmpty()) {
            deal.setEventDates(eventDatesToJson(request.eventDates));
            // Also set legacy eventDate to first date for backward compatibility
            if (!request.eventDates.isEmpty()) {
                try {
                    deal.setEventDate(java.time.LocalDate.parse(request.eventDates.get(0)));
                } catch (Exception e) {
                    // Ignore parse errors
                }
            }
        } else if (request.eventDate != null && !request.eventDate.isEmpty()) {
            // Legacy support: single date
            deal.setEventDate(java.time.LocalDate.parse(request.eventDate));
            // Convert to eventDates format
            List<String> singleDateList = List.of(request.eventDate);
            deal.setEventDates(eventDatesToJson(singleDateList));
        }
        // Handle label field with validation (legacy enum - read-only, kept for backward compatibility)
        if (request.label != null && !request.label.trim().isEmpty()) {
            DealLabel labelEnum = DealLabel.fromString(request.label);
            if (labelEnum == null) {
                throw new BadRequestException("Invalid label value: " + request.label + 
                    ". Allowed values: DIRECT, DIVERT, DESTINATION, PARTY MAKEUP, PRE WEDDING, BRIDAL MAKEUP");
            }
            deal.setLabelEnum(labelEnum);
            
            // If label is DIVERT, set is_diverted to true
            if (labelEnum == DealLabel.DIVERT) {
                deal.setIsDiverted(Boolean.TRUE);
                // Validate that referencedDealId is provided when diverting
                if (request.referencedDealId == null) {
                    throw new BadRequestException("referencedDealId is required when label is DIVERT");
                }
            }
        }

        // Handle custom labels (multiple labels from labels table)
        // Support both labelIds (new) and labelId (deprecated, for backward compatibility)
        List<Long> labelIdsToProcess = request.labelIds != null && !request.labelIds.isEmpty() 
            ? request.labelIds 
            : (request.labelId != null ? List.of(request.labelId) : null);
        
        if (labelIdsToProcess != null && !labelIdsToProcess.isEmpty()) {
            Set<com.brideside.crm.entity.Label> labels = new HashSet<>();
            for (Long labelId : labelIdsToProcess) {
                com.brideside.crm.entity.Label label = labelRepository.findById(labelId)
                        .orElseThrow(() -> new BadRequestException("Label not found with id: " + labelId));
                
                // Verify label is not deleted
                if (label.getIsDeleted() != null && label.getIsDeleted()) {
                    throw new BadRequestException("Label with id " + labelId + " has been deleted");
                }
                
                labels.add(label);
            }
            deal.setLabels(labels);
        }
        
        // Handle referenced deal (for diversion)
        if (request.referencedDealId != null) {
            Deal referencedDeal = dealRepository.findById(request.referencedDealId)
                .orElseThrow(() -> new ResourceNotFoundException("Referenced deal not found with id " + request.referencedDealId));
            
            // Check if referenced deal is deleted
            if (referencedDeal.getIsDeleted() != null && referencedDeal.getIsDeleted()) {
                throw new ResourceNotFoundException("Referenced deal not found with id " + request.referencedDealId);
            }
            
            deal.setReferencedDeal(referencedDeal);

            // Divert dialog only sends the target pipeline(s), not a name.
            // Inherit the name from the source deal so the NOT NULL constraint on deals.name is satisfied.
            if (deal.getName() == null || deal.getName().trim().isEmpty()) {
                deal.setName(referencedDeal.getName());
            }
            
            // Set the referenced pipeline to the original pipeline (traverse up the chain if needed)
            Pipeline originalPipeline = getOriginalPipeline(referencedDeal);
            if (originalPipeline != null) {
                deal.setReferencedPipeline(originalPipeline);
            }
            
            // Track pipeline history for diversion prevention
            Pipeline sourcePipeline = getSourcePipeline(referencedDeal);
            if (sourcePipeline != null) {
                deal.setSourcePipeline(sourcePipeline);
            }
            
            // Build pipeline history: get history from referenced deal and add its current pipeline
            List<Long> pipelineHistory = getPipelineHistory(referencedDeal);
            if (referencedDeal.getPipeline() != null) {
                Long currentPipelineId = referencedDeal.getPipeline().getId();
                if (!pipelineHistory.contains(currentPipelineId)) {
                    pipelineHistory.add(currentPipelineId);
                }
            }
            deal.setPipelineHistory(pipelineHistoryToJson(pipelineHistory));
        }
        
        // For non-diverted deals, set source pipeline to current pipeline if not set
        if (request.referencedDealId == null && deal.getPipeline() != null) {
            deal.setSourcePipeline(deal.getPipeline());
            // Initialize pipeline history with current pipeline
            List<Long> initialHistory = new ArrayList<>();
            initialHistory.add(deal.getPipeline().getId());
            deal.setPipelineHistory(pipelineHistoryToJson(initialHistory));
        }
        
        // Handle source field with validation
        if (request.source != null && !request.source.trim().isEmpty()) {
            DealSource dealSource = DealSource.fromString(request.source);
            if (dealSource == null) {
                throw new BadRequestException("Invalid source value: " + request.source + 
                    ". Allowed values: Direct, Divert, Reference, Planner, TBS");
            }
            deal.setDealSource(dealSource);
            
            // Handle subSource - only valid when source is "Direct"
            if (request.subSource != null && !request.subSource.trim().isEmpty()) {
                if (dealSource != DealSource.DIRECT) {
                    throw new BadRequestException("subSource can only be provided when source is 'Direct'");
                }
                DealSubSource dealSubSource = DealSubSource.fromString(request.subSource);
                if (dealSubSource == null) {
                    throw new BadRequestException("Invalid subSource value: " + request.subSource + 
                        ". Allowed values: Instagram, Whatsapp, Landing Page, Email");
                }
                deal.setDealSubSource(dealSubSource);
            } else if (dealSource == DealSource.DIRECT) {
                // Clear subSource if source is Direct but no subSource provided
                deal.setDealSubSource(null);
            }
        } else {
            // If source is cleared, also clear subSource
            deal.setDealSubSource(null);
        }
        
        // Handle createdBy fields
        if (request.createdBy != null) {
            deal.setCreatedBy(request.createdBy);
        } else {
            // Default to USER if not specified
            deal.setCreatedBy(CreatedByType.USER);
        }
        
        // If created by USER, get user info from authentication context or request
        if (deal.getCreatedBy() == CreatedByType.USER) {
            User user = null;
            
            // First, try to get from request if provided
            if (request.createdByUserId != null) {
                user = userRepository.findById(request.createdByUserId).orElse(null);
            }
            
            // If not in request, try to get from authentication context
            if (user == null) {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.getPrincipal() instanceof UserDetails) {
                    String email = ((UserDetails) authentication.getPrincipal()).getUsername();
                    user = userRepository.findByEmail(email).orElse(null);
                }
            }
            
            // Set user info if found
            if (user != null) {
                deal.setCreatedByUserId(user.getId());
                deal.setCreatedByName(user.getFirstName() + " " + user.getLastName());
            } else {
                log.warn("Could not determine user for USER-created deal. createdByUserId and createdByName will be null.");
            }
        } else if (deal.getCreatedBy() == CreatedByType.BOT) {
            // For bot-created deals, set createdByUserId to null
            deal.setCreatedByUserId(null);
            deal.setCreatedByName(null);
        }

        resolveAndApplyInitialStage(deal, request.stageId, request.phoneNumber);

        if (deal.getPipeline() != null && deal.getOrganization() == null) {
            deal.setOrganization(deal.getPipeline().getOrganization());
        }

        // Deal owner follows the pipeline's team manager.
        // - Diverted deals always adopt the target pipeline's team manager.
        // - New deals default to the pipeline's team manager when no owner was explicitly chosen.
        boolean divertedDeal = Boolean.TRUE.equals(deal.getIsDiverted()) || request.referencedDealId != null;
        User pipelineTeamManager = resolvePipelineTeamManager(deal);
        if (pipelineTeamManager != null && (divertedDeal || deal.getOwner() == null)) {
            deal.setOwner(pipelineTeamManager);
        }

        if (deal.getOwner() == null) {
            resolveDealOwnerFromPersonOrOrganization(deal);
        }
        
        Deal savedDeal = dealRepository.save(deal);
        
        // Record stage entry if stage is set
        if (savedDeal.getStage() != null) {
            dealStageHistoryService.recordStageEntry(savedDeal, savedDeal.getStage());
        }
        
        // Create activities ONLY if created by BOT and in Qualified stage
        // Frontend will handle activity creation for USER-created deals
        if (savedDeal.getCreatedBy() == CreatedByType.BOT && savedDeal.getStage() != null 
            && "Qualified".equalsIgnoreCase(savedDeal.getStage().getName())) {
            createQualifiedStageActivities(savedDeal);
        }
        
        syncGoogleCalendarEvent(savedDeal);
        return savedDeal;
    }

    @Override
    @Transactional
    public Deal update(Long id, DealDtos.UpdateRequest request) {
        Deal deal = get(id); // This will check if deal is deleted
        
        // Capture old stage before any updates
        Stage oldStage = deal.getStage();

        boolean reassignActivitiesToNewOwner = false;
        
        // Update fields only if provided (partial update)
        if (request.name != null) {
            deal.setName(request.name);
        }
        if (request.value != null) {
            deal.setValue(request.value);
        }
        if (request.personId != null) {
            Person person = personRepository.findById(request.personId)
                .orElseThrow(() -> new ResourceNotFoundException("Person not found"));
            // Check if person is soft-deleted
            if (Boolean.TRUE.equals(person.getIsDeleted())) {
                throw new ResourceNotFoundException("Person not found");
            }
            deal.setPerson(person);
            String phone = person.getPhone();
            if (phone != null) {
                deal.setContactNumber(phone);
            }
        }
        if (request.pipelineId != null) {
            Pipeline pipeline = pipelineRepository.findById(request.pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline not found"));
            deal.setPipeline(pipeline);
        }
        if (request.stageId != null) {
            Stage stage = stageRepository.findById(request.stageId)
                .orElseThrow(() -> new ResourceNotFoundException("Stage not found"));
            deal.setStage(stage);
            // If stage is changed to "Contact Made", set venueAsked to true
            if ("Contact Made".equalsIgnoreCase(stage.getName())) {
                deal.setVenueAsked(true);
            }
        }
        if (request.sourceId != null) {
            Source source = sourceRepository.findById(request.sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Source not found"));
            deal.setSource(source);
            if (deal.getValue() != null) {
                deal.setCommissionAmount(calculateCommission(deal.getValue(), source));
            }
        }
        if (request.organizationId != null) {
            Organization organization = organizationRepository.findById(request.organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
            deal.setOrganization(organization);
        }
        if (request.ownerId != null) {
            Long previousOwnerId = deal.getOwner() != null ? deal.getOwner().getId() : null;
            User owner = userRepository.findById(request.ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found"));
            // Explicit override for the deal row only; does not update person ownership.
            deal.setOwner(owner);
            if (!Objects.equals(previousOwnerId, owner.getId())) {
                reassignActivitiesToNewOwner = true;
            }
        }
        
        // Handle category
        if (request.categoryId != null || request.category != null) {
            Category category = resolveCategoryFromStrings(request.categoryId, request.category);
            if (category != null) {
                deal.setDealCategory(category);
            }
        }
        
        if (request.eventType != null) {
            deal.setEventType(request.eventType);
        }
        if (request.status != null) {
            deal.setStatus(request.status);
            // Sync legacy 'won' column
            deal.setLegacyWon(request.status == DealStatus.WON);
            if (request.status == DealStatus.WON) {
                deal.setContractShared(Boolean.TRUE);
                deal.setWhatsappGroupCreated(Boolean.TRUE);
                deal.setWonAt(LocalDateTime.now());
                deal.setLostAt(null);
            } else if (request.status == DealStatus.LOST) {
                deal.setContractShared(null);
                deal.setWhatsappGroupCreated(null);
                deal.setLostAt(LocalDateTime.now());
                deal.setWonAt(null);
            } else {
                deal.setContractShared(null);
                deal.setWhatsappGroupCreated(null);
                deal.setWonAt(null);
                deal.setLostAt(null);
            }
        }
        if (request.commissionAmount != null) {
            deal.setCommissionAmount(request.commissionAmount);
        }
        if (request.venue != null) {
            deal.setVenue(request.venue);
            // Update the linked person's venue if the deal has a person
            if (deal.getPerson() != null) {
                Person person = deal.getPerson();
                person.setVenue(request.venue);
                personRepository.save(person);
            }
        }
        if (request.phoneNumber != null) {
            deal.setPhoneNumber(request.phoneNumber);
        }
        if (request.city != null) {
            deal.setCity(request.city);
        }
        if (request.notes != null) {
            deal.setNotes(request.notes);
        }
        if (request.finalThankYouSent != null) {
            deal.setFinalThankYouSent(request.finalThankYouSent);
        }
        if (request.eventDateAsked != null) {
            deal.setEventDateAsked(request.eventDateAsked);
        }
        if (request.contactNumberAsked != null) {
            deal.setContactNumberAsked(request.contactNumberAsked);
        }
        if (request.venueAsked != null) {
            deal.setVenueAsked(request.venueAsked);
        }
        if (request.clientBudget != null) {
            deal.setClientBudget(request.clientBudget);
        }
        if (request.wonAt != null && !request.wonAt.isBlank()) {
            try {
                String s = request.wonAt.trim().replace(" ", "T");
                if (s.length() <= 10) {
                    deal.setWonAt(LocalDate.parse(s).atStartOfDay());
                } else {
                    deal.setWonAt(LocalDateTime.parse(s));
                }
            } catch (Exception e) {
                // Ignore parse errors
            }
        }
        if (request.lostAt != null && !request.lostAt.isBlank()) {
            try {
                String s = request.lostAt.trim().replace(" ", "T");
                if (s.length() <= 10) {
                    deal.setLostAt(LocalDate.parse(s).atStartOfDay());
                } else {
                    deal.setLostAt(LocalDateTime.parse(s));
                }
            } catch (Exception e) {
                // Ignore parse errors
            }
        }
        
        // Handle multiple event dates (preferred)
        if (request.eventDates != null && !request.eventDates.isEmpty()) {
            deal.setEventDates(eventDatesToJson(request.eventDates));
            // Also set legacy eventDate to first date for backward compatibility
            if (!request.eventDates.isEmpty()) {
                try {
                    deal.setEventDate(java.time.LocalDate.parse(request.eventDates.get(0)));
                } catch (Exception e) {
                    // Ignore parse errors
                }
            }
        } else if (request.eventDate != null && !request.eventDate.isEmpty()) {
            // Legacy support: single date
            deal.setEventDate(java.time.LocalDate.parse(request.eventDate));
            // Convert to eventDates format
            List<String> singleDateList = List.of(request.eventDate);
            deal.setEventDates(eventDatesToJson(singleDateList));
        }
        
        // Handle label field with validation (legacy enum - read-only, kept for backward compatibility)
        if (request.label != null && !request.label.trim().isEmpty()) {
            DealLabel labelEnum = DealLabel.fromString(request.label);
            if (labelEnum == null) {
                throw new BadRequestException("Invalid label value: " + request.label + 
                    ". Allowed values: DIRECT, DIVERT, DESTINATION, PARTY MAKEUP, PRE WEDDING, BRIDAL MAKEUP");
            }
            deal.setLabelEnum(labelEnum);
            
            // If label is DIVERT, set is_diverted to true
            if (labelEnum == DealLabel.DIVERT) {
                deal.setIsDiverted(Boolean.TRUE);
            } else {
                deal.setIsDiverted(Boolean.FALSE);
            }
        }

        // Handle custom labels (multiple labels from labels table)
        // Support both labelIds (new) and labelId (deprecated, for backward compatibility)
        // If labelIds is provided (even if empty), update the labels
        // If only labelId is provided, use it for backward compatibility
        // If neither is provided, don't change existing labels
        if (request.labelIds != null) {
            // labelIds was explicitly provided (could be empty list to clear labels)
            Set<com.brideside.crm.entity.Label> labels = new HashSet<>();
            if (!request.labelIds.isEmpty()) {
                for (Long labelId : request.labelIds) {
                    com.brideside.crm.entity.Label label = labelRepository.findById(labelId)
                            .orElseThrow(() -> new BadRequestException("Label not found with id: " + labelId));
                    
                    // Verify label is not deleted
                    if (label.getIsDeleted() != null && label.getIsDeleted()) {
                        throw new BadRequestException("Label with id " + labelId + " has been deleted");
                    }
                    
                    labels.add(label);
                }
            }
            deal.setLabels(labels);
        } else if (request.labelId != null) {
            // Backward compatibility: single labelId
            com.brideside.crm.entity.Label label = labelRepository.findById(request.labelId)
                    .orElseThrow(() -> new BadRequestException("Label not found with id: " + request.labelId));
            
            // Verify label is not deleted
            if (label.getIsDeleted() != null && label.getIsDeleted()) {
                throw new BadRequestException("Label with id " + request.labelId + " has been deleted");
            }
            
            Set<com.brideside.crm.entity.Label> labels = new HashSet<>();
            labels.add(label);
            deal.setLabels(labels);
        }
        // If neither labelIds nor labelId is provided, keep existing labels unchanged
        
        // Handle source field with validation
        if (request.source != null && !request.source.trim().isEmpty()) {
            DealSource dealSource = DealSource.fromString(request.source);
            if (dealSource == null) {
                throw new BadRequestException("Invalid source value: " + request.source + 
                    ". Allowed values: Direct, Divert, Reference, Planner, TBS");
            }
            deal.setDealSource(dealSource);
            
            // Handle subSource - only valid when source is "Direct"
            if (request.subSource != null && !request.subSource.trim().isEmpty()) {
                if (dealSource != DealSource.DIRECT) {
                    throw new BadRequestException("subSource can only be provided when source is 'Direct'");
                }
                DealSubSource dealSubSource = DealSubSource.fromString(request.subSource);
                if (dealSubSource == null) {
                    throw new BadRequestException("Invalid subSource value: " + request.subSource + 
                        ". Allowed values: Instagram, Whatsapp, Landing Page, Email");
                }
                deal.setDealSubSource(dealSubSource);
            } else if (dealSource == DealSource.DIRECT) {
                // Clear subSource if source is Direct but no subSource provided
                deal.setDealSubSource(null);
            }
        } else if (request.source != null) {
            // If source is explicitly set to null/empty, clear subSource
            deal.setDealSubSource(null);
        }
        
        // Track stage change before saving
        boolean stageChanged = false;
        if (request.stageId != null) {
            Stage newStage = deal.getStage(); // Already set above
            if (oldStage == null || newStage == null || !oldStage.getId().equals(newStage.getId())) {
                stageChanged = true;
            }
        }

        if (deal.getCreatedBy() == CreatedByType.BOT && deal.getPipeline() != null) {
            if (request.stageId == null) {
                stageChanged = maybePromoteBotDealOnPhoneReceived(deal, oldStage) || stageChanged;
            } else {
                validateStageBelongsToPipeline(deal.getStage(), deal.getPipeline());
            }
        } else if (request.stageId != null && deal.getPipeline() != null) {
            validateStageBelongsToPipeline(deal.getStage(), deal.getPipeline());
        }
        
        deal.setUpdatedAt(LocalDateTime.now());
        Deal savedDeal = dealRepository.save(deal);

        if (reassignActivitiesToNewOwner) {
            reassignIncompleteActivitiesToDealOwner(savedDeal);
        }
        
        // Record stage change if stage was updated
        if (stageChanged && savedDeal.getStage() != null) {
            dealStageHistoryService.recordStageEntry(savedDeal, savedDeal.getStage());
            
            // Create activities ONLY if:
            // 1. Deal was created by BOT (backend handles it)
            // 2. Stage changed from "Lead In" to "Qualified"
            // Frontend will handle activity creation for USER-created deals
            if (savedDeal.getCreatedBy() == CreatedByType.BOT 
                && oldStage != null && "Lead In".equalsIgnoreCase(oldStage.getName()) 
                && "Qualified".equalsIgnoreCase(savedDeal.getStage().getName())) {
                createQualifiedStageActivities(savedDeal);
            }

            maybeCreateBridesideDivertedCopy(savedDeal, oldStage, savedDeal.getStage());
        }
        
        syncGoogleCalendarEvent(savedDeal);
        return savedDeal;
    }

    @Override
    @Transactional(readOnly = true)
    public Deal get(Long id) {
        // Use findByIdWithLabel to eagerly fetch the label relationship
        Deal deal = dealRepository.findByIdWithLabel(id)
                .orElseThrow(() -> new ResourceNotFoundException("Deal not found"));
        // Check if deal is deleted
        if (deal.getIsDeleted() != null && deal.getIsDeleted()) {
            throw new ResourceNotFoundException("Deal not found");
        }
        // Ensure API-required relationships are initialized within a transaction
        initializeForApi(List.of(deal));
        return deal;
    }

    @Override
    public List<Deal> list() { 
        return list("nextActivity", "asc");
    }

    @Override
    public List<Deal> list(String sortField, String sortDirection) {
        // Call the new filtered list method with all filters as null, and no pagination (null limit/offset)
        return list(null, null, null, null, null, null, null, null, null, sortField, sortDirection, null, null, null, null);
    }

    /**
     * Builds a specification with all the provided filters
     */
    private Specification<Deal> buildSpecification(Long pipelineId, String status, Long organizationId, 
                                                    Long categoryId, Long managerId, String dateFrom, 
                                                    String dateTo, String search, String source, Long stageId,
                                                    Long personId) {
        // Parse and validate source if provided
        com.brideside.crm.entity.DealSource dealSource = null;
        if (source != null && !source.trim().isEmpty()) {
            dealSource = com.brideside.crm.entity.DealSource.fromString(source);
            if (dealSource == null) {
                throw new com.brideside.crm.exception.BadRequestException("Invalid source value: " + source + 
                    ". Allowed values: Direct, Divert, Reference, Planner, TBS");
            }
        }
        
        // Build specification with all filters
        Specification<Deal> spec = Specification.where(DealSpecifications.notDeleted())
                .and(DealSpecifications.hasPipeline(pipelineId))
                .and(DealSpecifications.hasStatus(status))
                .and(DealSpecifications.hasOrganization(organizationId))
                .and(DealSpecifications.hasCategory(categoryId))
                .and(DealSpecifications.hasManager(managerId))
                .and(DealSpecifications.hasStage(stageId))
                .and(DealSpecifications.hasPerson(personId))
                .and(DealSpecifications.search(search))
                .and(DealSpecifications.hasSource(dealSource));

        // Parse date filters
        LocalDate fromDate = null;
        LocalDate toDate = null;
        if (dateFrom != null && !dateFrom.trim().isEmpty()) {
            try {
                fromDate = LocalDate.parse(dateFrom.trim());
            } catch (Exception e) {
                log.warn("Invalid dateFrom format: {}. Expected YYYY-MM-DD", dateFrom);
            }
        }
        if (dateTo != null && !dateTo.trim().isEmpty()) {
            try {
                toDate = LocalDate.parse(dateTo.trim());
            } catch (Exception e) {
                log.warn("Invalid dateTo format: {}. Expected YYYY-MM-DD", dateTo);
            }
        }
        spec = spec.and(DealSpecifications.createdBetween(fromDate, toDate));
        
        return spec;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Deal> list(Long pipelineId, String status, Long organizationId, Long categoryId,
                           Long managerId, String dateFrom, String dateTo, String search, String source,
                           String sortField, String sortDirection, Integer limit, Integer offset, Long stageId,
                           Long personId) {
        log.debug("Deal list requested with filters: pipelineId={}, status={}, organizationId={}, categoryId={}, managerId={}, dateFrom={}, dateTo={}, search={}, source={}, stageId={}, personId={}, sort={},{}, limit={}, offset={}", 
            pipelineId, status, organizationId, categoryId, managerId, dateFrom, dateTo, search, source, stageId, personId, sortField, sortDirection, limit, offset);
        
        // Build specification with all filters
        Specification<Deal> spec = buildSpecification(pipelineId, status, organizationId, categoryId, 
                                                       managerId, dateFrom, dateTo, search, source, stageId, personId);
        
        log.debug("Deal list: Applied filters - pipelineId={}, status={}, organizationId={}, categoryId={}, managerId={}, dateFrom={}, dateTo={}, search={}, source={}, stageId={}, personId={}", 
            pipelineId, status, organizationId, categoryId, managerId, dateFrom, dateTo, search, source, stageId, personId);

        // Load deals with specification.
        // NOTE: Sorting/pagination semantics are implemented in Java to preserve existing behavior.
        List<Deal> deals = dealRepository.findAll(spec);
        log.debug("Deal list: Found {} deals after applying filters", deals.size());
        
        // Normalize sort field and direction
        String normalizedField = normalizeSortField(sortField);
        boolean ascending = "asc".equalsIgnoreCase(sortDirection != null ? sortDirection : "asc");
        
        // Validate sort field
        if (!isValidSortField(normalizedField)) {
            throw new BadRequestException("Invalid sort field: " + sortField + 
                ". Supported fields: nextActivity, name, value, personName, organizationName, eventDate, createdAt, updatedAt, completedActivitiesCount, pendingActivitiesCount, productsCount, ownerName");
        }
        
        // Determine pagination bounds up-front (used for partial sorting optimization)
        int totalSize = deals.size();
        int limitValue = (limit != null && limit > 0) ? limit : 100; // Default limit: 100
        int offsetValue = (offset != null && offset >= 0) ? offset : 0; // Default offset: 0

        if (totalSize == 0 || offsetValue >= totalSize) {
            return new ArrayList<>();
        }

        // Pre-load activities only if needed for sorting (performance optimization)
        Map<Long, List<com.brideside.crm.entity.Activity>> activitiesByDealId = null;
        if (normalizedField.equals("nextActivity") || 
            normalizedField.equals("completedActivitiesCount") || 
            normalizedField.equals("pendingActivitiesCount")) {
            Set<Long> dealIds = deals.stream()
                .map(Deal::getId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
            activitiesByDealId = loadActivitiesByDealId(dealIds);
        }
        
        // Sort deals based on the field (stable) and apply pagination.
        // Optimization: for large datasets and small pages, avoid sorting the full list.
        //
        // Also: precompute activity-based sort keys to avoid repeatedly scanning activities during comparisons.
        Comparator<Deal> comparator;
        if ("nextActivity".equals(normalizedField) && activitiesByDealId != null) {
            Map<Long, LocalDateTime> nextByDealId = new HashMap<>(Math.max(16, deals.size() * 2));
            for (Deal d : deals) {
                Long id = d != null ? d.getId() : null;
                if (id == null) continue;
                List<com.brideside.crm.entity.Activity> acts = activitiesByDealId.get(id);
                LocalDateTime next = null;
                if (acts != null) {
                    for (com.brideside.crm.entity.Activity a : acts) {
                        if (a == null) continue;
                        if (a.isDone() || a.getStatus() == com.brideside.crm.entity.Activity.ActivityStatus.COMPLETED) {
                            continue;
                        }
                        LocalDateTime dt = parseActivityDate(a);
                        if (dt == null) continue;
                        if (next == null || dt.isBefore(next)) {
                            next = dt;
                        }
                    }
                }
                nextByDealId.put(id, next);
            }
            Comparator<Deal> base = Comparator.comparing(
                d -> d != null ? nextByDealId.get(d.getId()) : null,
                Comparator.nullsLast(Comparator.naturalOrder())
            );
            comparator = ascending ? base : base.reversed();
        } else if ("completedActivitiesCount".equals(normalizedField) && activitiesByDealId != null) {
            Map<Long, Integer> completedByDealId = new HashMap<>(Math.max(16, deals.size() * 2));
            for (Deal d : deals) {
                Long id = d != null ? d.getId() : null;
                if (id == null) continue;
                List<com.brideside.crm.entity.Activity> acts = activitiesByDealId.get(id);
                int count = 0;
                if (acts != null) {
                    for (com.brideside.crm.entity.Activity a : acts) {
                        if (a == null) continue;
                        if (a.isDone() || a.getStatus() == com.brideside.crm.entity.Activity.ActivityStatus.COMPLETED) {
                            count++;
                        }
                    }
                }
                completedByDealId.put(id, count);
            }
            Comparator<Deal> base = Comparator.comparing(
                d -> {
                    if (d == null || d.getId() == null) return 0;
                    return completedByDealId.getOrDefault(d.getId(), 0);
                },
                Comparator.nullsLast(Comparator.naturalOrder())
            );
            comparator = ascending ? base : base.reversed();
        } else if ("pendingActivitiesCount".equals(normalizedField) && activitiesByDealId != null) {
            Map<Long, Integer> pendingByDealId = new HashMap<>(Math.max(16, deals.size() * 2));
            for (Deal d : deals) {
                Long id = d != null ? d.getId() : null;
                if (id == null) continue;
                List<com.brideside.crm.entity.Activity> acts = activitiesByDealId.get(id);
                int count = 0;
                if (acts != null) {
                    for (com.brideside.crm.entity.Activity a : acts) {
                        if (a == null) continue;
                        if (!a.isDone() && a.getStatus() != com.brideside.crm.entity.Activity.ActivityStatus.COMPLETED) {
                            count++;
                        }
                    }
                }
                pendingByDealId.put(id, count);
            }
            Comparator<Deal> base = Comparator.comparing(
                d -> {
                    if (d == null || d.getId() == null) return 0;
                    return pendingByDealId.getOrDefault(d.getId(), 0);
                },
                Comparator.nullsLast(Comparator.naturalOrder())
            );
            comparator = ascending ? base : base.reversed();
        } else {
            comparator = getComparator(normalizedField, ascending, activitiesByDealId);
        }
        List<Deal> paginatedDeals = selectStablePage(deals, comparator, offsetValue, limitValue);

        // Initialize relationships needed by API response mapping (controller) to avoid lazy-loading
        // outside this transactional context (production-ready even with open-in-view disabled).
        initializeForApi(paginatedDeals);
        
        log.debug("Deal list: Returning {} deals (page: offset={}, limit={}) out of {} total deals after sorting by {} {}", 
            paginatedDeals.size(), offsetValue, limitValue, totalSize, normalizedField, sortDirection);
        return paginatedDeals;
    }

    @Override
    @Transactional(readOnly = true)
    public long count(Long pipelineId, String status, Long organizationId, Long categoryId,
                      Long managerId, String dateFrom, String dateTo, String search, String source, Long stageId,
                      Long personId) {
        log.debug("Deal count requested with filters: pipelineId={}, status={}, organizationId={}, categoryId={}, managerId={}, dateFrom={}, dateTo={}, search={}, source={}, stageId={}, personId={}", 
            pipelineId, status, organizationId, categoryId, managerId, dateFrom, dateTo, search, source, stageId, personId);
        
        // Build specification with all filters (same as list method)
        Specification<Deal> spec = buildSpecification(pipelineId, status, organizationId, categoryId, 
                                                       managerId, dateFrom, dateTo, search, source, stageId, personId);
        
        // Count deals matching the specification
        long count = dealRepository.count(spec);
        log.debug("Deal count: Found {} deals matching filters", count);
        return count;
    }

    @Override
    @Transactional(readOnly = true)
    public DealDtos.RevenueResponse calculateRevenue(Long pipelineId, String status, Long organizationId, 
                                                      Long categoryId, Long managerId, String dateFrom, 
                                                      String dateTo, String search, String source, Long stageId) {
        log.debug("Revenue calculation requested with filters: pipelineId={}, status={}, organizationId={}, categoryId={}, managerId={}, dateFrom={}, dateTo={}, search={}, source={}, stageId={}", 
            pipelineId, status, organizationId, categoryId, managerId, dateFrom, dateTo, search, source, stageId);
        
        // Build specification with all filters (same as count method)
        Specification<Deal> spec = buildSpecification(pipelineId, status, organizationId, categoryId, 
                                                       managerId, dateFrom, dateTo, search, source, stageId, null);
        
        // Calculate sum of deal values using Criteria API
        BigDecimal totalRevenue = calculateSumWithSpecification(spec);
        long dealCount = dealRepository.count(spec);
        
        log.debug("Revenue calculation: Total revenue={}, Deal count={}", totalRevenue, dealCount);
        return new DealDtos.RevenueResponse(totalRevenue, dealCount);
    }

    @Override
    @Transactional(readOnly = true)
    public DealDtos.LostDealsByStageResponse getLostDealsByStage() {
        log.debug("Lost deals by stage requested");
        // Overall lost count per stage
        List<Object[]> rows = dealRepository.countLostDealsByStage();
        Map<Long, DealDtos.LostDealsByStageItem> byStage = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Long stageId = row[0] instanceof Number ? ((Number) row[0]).longValue() : null;
            String stageName = (String) row[1];
            Long lostCount = row[2] instanceof Number ? ((Number) row[2]).longValue() : 0L;
            DealDtos.LostDealsByStageItem item = new DealDtos.LostDealsByStageItem(stageId, stageName, lostCount);
            item.categories = new ArrayList<>();
            if (stageId != null) {
                byStage.put(stageId, item);
            }
        }

        // Category-wise lost count per stage
        List<Object[]> catRows = dealRepository.countLostDealsByStageAndCategory();
        for (Object[] row : catRows) {
            Long stageId = row[0] instanceof Number ? ((Number) row[0]).longValue() : null;
            Object catEnum = row[2];
            Long lostCount = row[3] instanceof Number ? ((Number) row[3]).longValue() : 0L;

            if (stageId == null) {
                continue;
            }
            DealDtos.LostDealsByStageItem parent = byStage.get(stageId);
            if (parent == null) {
                // In case stage wasn't present in overall query for some reason
                String stageName = (String) row[1];
                parent = new DealDtos.LostDealsByStageItem(stageId, stageName, 0L);
                parent.categories = new ArrayList<>();
                byStage.put(stageId, parent);
            }

            String categoryKey = null;
            if (catEnum instanceof Organization.OrganizationCategory orgCat) {
                categoryKey = orgCat.getDbValue();
            }
            parent.categories.add(new DealDtos.LostDealsByStageCategoryItem(categoryKey, lostCount));
        }

        // Average days from creation to LOST per stage
        List<Deal> lostDeals = dealRepository.findByStatusAndIsDeletedFalse(DealStatus.LOST);
        Map<Long, long[]> durationAgg = new HashMap<>(); // stageId -> [sumDays, count]
        for (Deal d : lostDeals) {
            if (d.getStage() == null || d.getStage().getId() == null || d.getCreatedAt() == null) {
                continue;
            }
            Long stageId = d.getStage().getId();
            // Use lostAt if available; fallback to updatedAt/createdAt
            LocalDateTime reference = d.getLostAt() != null
                    ? d.getLostAt()
                    : (d.getUpdatedAt() != null ? d.getUpdatedAt() : d.getCreatedAt());
            if (reference == null) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(d.getCreatedAt(), reference);
            long[] agg = durationAgg.computeIfAbsent(stageId, id -> new long[] {0L, 0L});
            agg[0] += days;
            agg[1] += 1;
        }

        for (Map.Entry<Long, long[]> e : durationAgg.entrySet()) {
            Long stageId = e.getKey();
            long sumDays = e.getValue()[0];
            long count = e.getValue()[1];
            DealDtos.LostDealsByStageItem item = byStage.get(stageId);
            if (item != null && count > 0) {
                item.avgDaysToLost = sumDays / (double) count;
            }
        }

        return new DealDtos.LostDealsByStageResponse(new ArrayList<>(byStage.values()));
    }

    /**
     * Calculate sum of deal values using JPA Criteria API with the given specification.
     * This is more efficient than fetching all deals and summing in Java.
     */
    private BigDecimal calculateSumWithSpecification(Specification<Deal> spec) {
        jakarta.persistence.criteria.CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        jakarta.persistence.criteria.CriteriaQuery<BigDecimal> query = cb.createQuery(BigDecimal.class);
        jakarta.persistence.criteria.Root<Deal> root = query.from(Deal.class);
        
        // Build the sum expression
        query.select(cb.sum(root.get("value")));
        
        // Apply the specification predicates
        if (spec != null) {
            jakarta.persistence.criteria.Predicate predicate = spec.toPredicate(root, query, cb);
            if (predicate != null) {
                query.where(predicate);
            }
        }
        
        // Execute query and get result
        try {
            BigDecimal result = entityManager.createQuery(query).getSingleResult();
            return result != null ? result : BigDecimal.ZERO;
        } catch (jakarta.persistence.NoResultException e) {
            // No deals match the criteria, return zero
            return BigDecimal.ZERO;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DealDtos.UserDealTotalsResponse getUserDealTotals(Long pipelineId) {
        log.debug("User deal totals requested for pipelineId={}", pipelineId);

        // Reuse the filtered list() logic to respect soft-delete and filters.
        List<Deal> deals = list(
                pipelineId,
                null,          // status: all
                null,          // organizationId
                null,          // categoryId
                null,          // managerId (we derive owner from organization)
                null,          // dateFrom
                null,          // dateTo
                null,          // search
                null,          // source
                null,          // sortField
                null,          // sortDirection
                null,          // limit (no pagination)
                null,          // offset
                null,          // stageId
                null           // personId
        );

        Map<Long, DealDtos.UserDealTotals> totalsByUser = new HashMap<>();

        for (Deal deal : deals) {
            if (deal == null || deal.getOrganization() == null) {
                continue;
            }
            Organization org = deal.getOrganization();
            User owner = org.getOwner();
            if (owner == null || owner.getId() == null) {
                continue;
            }

            // Only consider SALES users for this aggregation.
            if (owner.getRole() == null || owner.getRole().getName() != Role.RoleName.SALES) {
                continue;
            }

            Long userId = owner.getId();
            String userName = (owner.getFirstName() != null ? owner.getFirstName() : "") + " " +
                              (owner.getLastName() != null ? owner.getLastName() : "");
            userName = userName.trim();

            DealDtos.UserDealTotals current = totalsByUser.get(userId);
            if (current == null) {
                current = new DealDtos.UserDealTotals(
                        userId,
                        userName,
                        0L,
                        BigDecimal.ZERO,
                        0L,
                        BigDecimal.ZERO,
                        0L,
                        BigDecimal.ZERO
                );
                totalsByUser.put(userId, current);
            }

            BigDecimal value = deal.getValue() != null ? deal.getValue() : BigDecimal.ZERO;

            // Update total counts and values
            current.totalCount = current.totalCount + 1;
            current.totalValue = current.totalValue.add(value);

            // Update won / lost buckets
            if (deal.getStatus() == DealStatus.WON) {
                current.wonCount = current.wonCount + 1;
                current.wonValue = current.wonValue.add(value);
            } else if (deal.getStatus() == DealStatus.LOST) {
                current.lostCount = current.lostCount + 1;
                current.lostValue = current.lostValue.add(value);
            }
        }

        List<DealDtos.UserDealTotals> users = new ArrayList<>(totalsByUser.values());
        // Optional: sort by userName for stable frontend display
        users.sort(Comparator.comparing(u -> u.userName != null ? u.userName.toLowerCase() : ""));

        return new DealDtos.UserDealTotalsResponse(users);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.brideside.crm.dto.PersonDTO> getPersonsByDealIds(List<Long> dealIds) {
        if (dealIds == null || dealIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        log.debug("Fetching persons for {} deals using JOIN", dealIds.size());
        
        // Efficiently fetch distinct person IDs without triggering N+1 lazy loads
        Set<Long> personIds = new HashSet<>(dealRepository.findDistinctPersonIdsByDealIds(dealIds));
        
        if (personIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Fetch persons with JOINs to avoid N+1 queries
        // SELECT p.* FROM persons p 
        // LEFT JOIN organizations o ON p.organization_id = o.id
        // LEFT JOIN users u ON p.owner_id = u.id
        // LEFT JOIN categories c ON p.category_id = c.id
        // LEFT JOIN labels l ON p.label_id = l.id
        // WHERE p.id IN (:personIds)
        Specification<Person> personSpec = (root, query, cb) -> {
            // Use fetch joins to eagerly load related entities
            root.fetch("organization", jakarta.persistence.criteria.JoinType.LEFT);
            root.fetch("owner", jakarta.persistence.criteria.JoinType.LEFT);
            root.fetch("category", jakarta.persistence.criteria.JoinType.LEFT);
            root.fetch("label", jakarta.persistence.criteria.JoinType.LEFT);
            return root.get("id").in(personIds);
        };
        
        List<Person> persons = personRepository.findAll(personSpec);
        log.debug("Found {} distinct persons for {} deals", persons.size(), dealIds.size());
        
        return persons.stream()
            .map(com.brideside.crm.mapper.PersonMapper::toDto)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.brideside.crm.dto.ActivityDTO> getActivitiesByDealIds(List<Long> dealIds) {
        if (dealIds == null || dealIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        log.debug("Fetching activities for {} deals using JOIN", dealIds.size());
        
        // Fetch activities with JOIN to organization to avoid N+1 queries
        // SELECT a.* FROM activities a 
        // LEFT JOIN organizations o ON a.organization_id = o.id
        // WHERE a.deal_id IN (:dealIds)
        Specification<Activity> spec = (root, query, cb) -> {
            // Use fetch join to eagerly load organization with its owner
            // Note: category is an enum field in Organization, not a relationship, so it's automatically loaded
            jakarta.persistence.criteria.Fetch<Activity, Organization> orgFetch = 
                root.fetch("organizationRef", jakarta.persistence.criteria.JoinType.LEFT);
            // Fetch organization's owner (category is an enum, not a relationship)
            orgFetch.fetch("owner", jakarta.persistence.criteria.JoinType.LEFT);
            return root.get("dealId").in(dealIds);
        };
        
        List<Activity> activities = activityRepository.findAll(spec);
        log.debug("Found {} activities for {} deals", activities.size(), dealIds.size());
        
        return activities.stream()
            .map(com.brideside.crm.mapper.ActivityMapper::toDto)
            .collect(Collectors.toList());
    }
    
    private Map<Long, List<com.brideside.crm.entity.Activity>> loadActivitiesByDealId(Set<Long> dealIds) {
        if (dealIds == null || dealIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // Fetch only activities belonging to the candidate deals (instead of loading the entire activities table).
        // Chunking avoids overly-large IN clauses.
        final int CHUNK_SIZE = 1000;
        List<Long> ids = new ArrayList<>(dealIds);
        Map<Long, List<com.brideside.crm.entity.Activity>> grouped = new HashMap<>();

        for (int i = 0; i < ids.size(); i += CHUNK_SIZE) {
            List<Long> chunk = ids.subList(i, Math.min(i + CHUNK_SIZE, ids.size()));
            Specification<com.brideside.crm.entity.Activity> spec = (root, query, cb) ->
                root.get("dealId").in(chunk);

            List<com.brideside.crm.entity.Activity> activities = activityRepository.findAll(spec);
            for (com.brideside.crm.entity.Activity a : activities) {
                if (a.getDealId() == null) continue;
                grouped.computeIfAbsent(a.getDealId(), __ -> new ArrayList<>()).add(a);
            }
        }

        return grouped;
    }

    /**
     * Selects a page from a list using the provided comparator, preserving the behavior of a stable sort.
     * This avoids sorting the entire list when only a small window is needed.
     */
    private List<Deal> selectStablePage(List<Deal> deals, Comparator<Deal> comparator, int offset, int limit) {
        int totalSize = deals.size();
        int fromIndex = Math.max(0, offset);
        int toIndexExclusive = Math.min(totalSize, fromIndex + Math.max(0, limit));
        if (fromIndex >= toIndexExclusive) {
            return new ArrayList<>();
        }

        // Stable sort tie-breaker: original index (matches TimSort stability used by List.sort()).
        record IndexedDeal(int index, Deal deal) {}

        Comparator<IndexedDeal> stableComparator = (a, b) -> {
            int c = comparator.compare(a.deal(), b.deal());
            if (c != 0) return c;
            return Integer.compare(a.index(), b.index());
        };

        int k = Math.min(totalSize, toIndexExclusive);
        PriorityQueue<IndexedDeal> heap = new PriorityQueue<>(k, stableComparator.reversed()); // max-heap of best k

        for (int i = 0; i < totalSize; i++) {
            IndexedDeal item = new IndexedDeal(i, deals.get(i));
            if (heap.size() < k) {
                heap.add(item);
            } else if (stableComparator.compare(item, heap.peek()) < 0) {
                heap.poll();
                heap.add(item);
            }
        }

        ArrayList<IndexedDeal> bestK = new ArrayList<>(heap);
        bestK.sort(stableComparator);

        // Now bestK is equivalent to deals sorted stably and truncated to the first k elements.
        List<Deal> page = new ArrayList<>(toIndexExclusive - fromIndex);
        for (int i = fromIndex; i < toIndexExclusive; i++) {
            page.add(bestK.get(i).deal());
        }
        return page;
    }

    private void initializeForApi(List<Deal> deals) {
        if (deals == null || deals.isEmpty()) return;

        for (Deal deal : deals) {
            if (deal == null) continue;

            if (deal.getPerson() != null) {
                deal.getPersonName();
                if (deal.getPerson().getOwner() != null) {
                    deal.getPerson().getOwner().getFirstName();
                    deal.getPerson().getOwner().getLastName();
                }
            }
            if (deal.getOrganization() != null) {
                deal.getOrganization().getName();
            }
            if (deal.getPipeline() != null) {
                deal.getPipeline().getName();
            }
            if (deal.getStage() != null) {
                deal.getStage().getName();
            }
            if (deal.getSource() != null) {
                // Source entity doesn't have getName(); touching an attribute forces initialization
                deal.getSource().getType();
            }
            if (deal.getDealCategory() != null) {
                deal.getDealCategory().getName();
            }
            if (deal.getOwner() != null) {
                deal.getOwner().getFirstName();
            }
            if (deal.getReferencedDeal() != null) {
                deal.getReferencedDeal().getId();
            }
            if (deal.getReferencedPipeline() != null) {
                deal.getReferencedPipeline().getId();
            }
            if (deal.getSourcePipeline() != null) {
                deal.getSourcePipeline().getId();
            }
            if (deal.getLabels() != null) {
                deal.getLabels().forEach(label -> label.getId());
            }
        }
    }
    
    private String normalizeSortField(String sortField) {
        if (sortField == null || sortField.trim().isEmpty()) {
            return "nextActivity";
        }
        String field = sortField.trim();
        String fieldLower = field.toLowerCase();
        
        // Map aliases to canonical field names (case-insensitive)
        Map<String, String> fieldMap = new HashMap<>();
        // Canonical field names (map to themselves)
        fieldMap.put("nextactivity", "nextActivity");
        fieldMap.put("name", "name");
        fieldMap.put("value", "value");
        fieldMap.put("personname", "personName");
        fieldMap.put("organizationname", "organizationName");
        fieldMap.put("eventdate", "eventDate");
        fieldMap.put("createdat", "createdAt");
        fieldMap.put("updatedat", "updatedAt");
        fieldMap.put("completedactivitiescount", "completedActivitiesCount");
        fieldMap.put("pendingactivitiescount", "pendingActivitiesCount");
        fieldMap.put("productscount", "productsCount");
        fieldMap.put("ownername", "ownerName");
        // Aliases
        fieldMap.put("dealtitle", "name");
        fieldMap.put("dealvalue", "value");
        fieldMap.put("linkedperson", "personName");
        fieldMap.put("linkedorganization", "organizationName");
        fieldMap.put("expectedclosedate", "eventDate");
        fieldMap.put("dealcreated", "createdAt");
        fieldMap.put("dealupdatetime", "updatedAt");
        fieldMap.put("doneactivities", "completedActivitiesCount");
        fieldMap.put("activitiestodo", "pendingActivitiesCount");
        fieldMap.put("numberofproducts", "productsCount");
        fieldMap.put("personownername", "ownerName");
        
        return fieldMap.getOrDefault(fieldLower, field);
    }
    
    private boolean isValidSortField(String field) {
        List<String> validFields = List.of(
            "nextActivity", "name", "value", "personName", "organizationName",
            "eventDate", "createdAt", "updatedAt", "completedActivitiesCount",
            "pendingActivitiesCount", "productsCount", "ownerName"
        );
        return validFields.contains(field);
    }
    
    private Comparator<Deal> getComparator(String field, boolean ascending, Map<Long, List<com.brideside.crm.entity.Activity>> activitiesByDealId) {
        Comparator<Deal> comparator = null;
        
        switch (field) {
            case "nextActivity":
                comparator = Comparator.comparing(d -> getNextActivityDate(d, activitiesByDealId), 
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "name":
                comparator = Comparator.comparing(Deal::getName, 
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                break;
            case "value":
                comparator = Comparator.comparing(Deal::getValue, 
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "personName":
                comparator = Comparator.comparing(this::getPersonName, 
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                break;
            case "organizationName":
                comparator = Comparator.comparing(this::getOrganizationName, 
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                break;
            case "eventDate":
                comparator = Comparator.comparing(d -> getFirstEventDate(d), 
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "createdAt":
                comparator = Comparator.comparing(Deal::getCreatedAt, 
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "updatedAt":
                comparator = Comparator.comparing(Deal::getUpdatedAt, 
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "completedActivitiesCount":
                comparator = Comparator.comparing(d -> getCompletedActivitiesCount(d, activitiesByDealId), 
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "pendingActivitiesCount":
                comparator = Comparator.comparing(d -> getPendingActivitiesCount(d, activitiesByDealId), 
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "productsCount":
                comparator = Comparator.comparing(d -> 0, 
                    Comparator.nullsLast(Comparator.naturalOrder())); // Always 0 for now
                break;
            case "ownerName":
                comparator = Comparator.comparing(this::getOwnerName, 
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                break;
            default:
                throw new BadRequestException("Unsupported sort field: " + field);
        }
        
        return ascending ? comparator : comparator.reversed();
    }
    
    private LocalDateTime getNextActivityDate(Deal deal, Map<Long, List<com.brideside.crm.entity.Activity>> activitiesByDealId) {
        if (activitiesByDealId == null || deal.getId() == null) {
            return null;
        }
        // Get the earliest pending activity date for this deal
        List<com.brideside.crm.entity.Activity> dealActivities = activitiesByDealId.get(deal.getId());
        if (dealActivities == null || dealActivities.isEmpty()) {
            return null;
        }
        return dealActivities.stream()
            .filter(a -> !a.isDone() && a.getStatus() != com.brideside.crm.entity.Activity.ActivityStatus.COMPLETED)
            .map(a -> parseActivityDate(a))
            .filter(d -> d != null)
            .min(Comparator.naturalOrder())
            .orElse(null);
    }
    
    private LocalDateTime parseActivityDate(com.brideside.crm.entity.Activity activity) {
        // Try dateTime first (ISO format)
        if (activity.getDateTime() != null && !activity.getDateTime().isEmpty()) {
            try {
                return LocalDateTime.parse(activity.getDateTime().replace(" ", "T"));
            } catch (Exception e) {
                // Try other formats
            }
        }
        
        // Try date + startTime
        if (activity.getDate() != null && !activity.getDate().isEmpty() && 
            activity.getStartTime() != null && !activity.getStartTime().isEmpty()) {
            try {
                DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
                DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
                LocalDate date = LocalDate.parse(activity.getDate(), dateFormatter);
                java.time.LocalTime time = java.time.LocalTime.parse(activity.getStartTime(), timeFormatter);
                return LocalDateTime.of(date, time);
            } catch (Exception e) {
                // Try date only
            }
        }
        
        // Try date only
        if (activity.getDate() != null && !activity.getDate().isEmpty()) {
            try {
                DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
                LocalDate date = LocalDate.parse(activity.getDate(), dateFormatter);
                return date.atStartOfDay();
            } catch (Exception e) {
                // Ignore
            }
        }
        
        // Try dueDate
        if (activity.getDueDate() != null && !activity.getDueDate().isEmpty()) {
            try {
                DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
                LocalDate date = LocalDate.parse(activity.getDueDate(), dateFormatter);
                return date.atStartOfDay();
            } catch (Exception e) {
                // Ignore
            }
        }
        
        return null;
    }
    
    private String getPersonName(Deal deal) {
        return deal.getPersonName();
    }
    
    private String getOrganizationName(Deal deal) {
        return deal.getOrganization() != null ? deal.getOrganization().getName() : null;
    }
    
    private Integer getCompletedActivitiesCount(Deal deal, Map<Long, List<com.brideside.crm.entity.Activity>> activitiesByDealId) {
        if (activitiesByDealId == null || deal.getId() == null) {
            return 0;
        }
        List<com.brideside.crm.entity.Activity> dealActivities = activitiesByDealId.get(deal.getId());
        if (dealActivities == null || dealActivities.isEmpty()) {
            return 0;
        }
        return (int) dealActivities.stream()
            .filter(a -> a.isDone() || a.getStatus() == com.brideside.crm.entity.Activity.ActivityStatus.COMPLETED)
            .count();
    }
    
    private Integer getPendingActivitiesCount(Deal deal, Map<Long, List<com.brideside.crm.entity.Activity>> activitiesByDealId) {
        if (activitiesByDealId == null || deal.getId() == null) {
            return 0;
        }
        List<com.brideside.crm.entity.Activity> dealActivities = activitiesByDealId.get(deal.getId());
        if (dealActivities == null || dealActivities.isEmpty()) {
            return 0;
        }
        return (int) dealActivities.stream()
            .filter(a -> !a.isDone() && a.getStatus() != com.brideside.crm.entity.Activity.ActivityStatus.COMPLETED)
            .count();
    }
    
    private String getOwnerName(Deal deal) {
        if (deal.getOwner() != null) {
            return deal.getOwner().getDisplayName();
        }
        if (deal.getPerson() != null && deal.getPerson().getOwner() != null) {
            return deal.getPerson().getOwner().getDisplayName();
        }
        return null;
    }
    
    /**
     * Gets the first event date from the eventDates JSON array, or falls back to legacy eventDate.
     */
    private LocalDate getFirstEventDate(Deal deal) {
        List<LocalDate> dates = parseEventDates(deal);
        if (dates != null && !dates.isEmpty()) {
            return dates.get(0);
        }
        // Fallback to legacy eventDate
        return deal.getEventDate();
    }
    
    /**
     * Parses eventDates JSON string to a list of LocalDate objects.
     */
    private List<LocalDate> parseEventDates(Deal deal) {
        if (deal.getEventDates() == null || deal.getEventDates().isEmpty()) {
            return null;
        }
        try {
            List<String> dateStrings = objectMapper.readValue(
                deal.getEventDates(),
                new TypeReference<List<String>>() {}
            );
            return dateStrings.stream()
                .map(dateStr -> {
                    try {
                        return LocalDate.parse(dateStr);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(date -> date != null)
                .collect(Collectors.toList());
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Converts a list of date strings to JSON string.
     */
    private String eventDatesToJson(List<String> dateStrings) {
        if (dateStrings == null || dateStrings.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(dateStrings);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<Deal> listWon() { 
        return dealRepository.findByStatusAndIsDeletedFalse(DealStatus.WON); 
    }

    @Override
    public List<Deal> listByStatus(DealStatus status) { 
        return dealRepository.findByStatusAndIsDeletedFalse(status); 
    }

    @Override
    public List<Deal> listByPerson(Long personId) {
        Person person = personRepository.findById(personId)
            .orElseThrow(() -> new ResourceNotFoundException("Person not found"));
        // Check if person is soft-deleted
        if (Boolean.TRUE.equals(person.getIsDeleted())) {
            throw new ResourceNotFoundException("Person not found");
        }
        return dealRepository.findByPersonAndIsDeletedFalse(person);
    }

    @Override
    @Transactional
    public void promoteBotDealsWhenPhoneReceived(Long personId) {
        if (personId == null) {
            return;
        }
        Person person = personRepository.findById(personId).orElse(null);
        if (person == null || !hasPhoneNumber(person.getPhone())) {
            return;
        }

        List<Deal> deals = dealRepository.findByPersonAndIsDeletedFalse(person);
        for (Deal deal : deals) {
            if (deal.getCreatedBy() != CreatedByType.BOT || deal.getPipeline() == null) {
                continue;
            }
            Stage oldStage = deal.getStage();
            if (maybePromoteBotDealOnPhoneReceived(deal, oldStage)) {
                Deal saved = dealRepository.save(deal);
                dealStageHistoryService.recordStageEntry(saved, saved.getStage());
                createQualifiedStageActivities(saved);
            }
        }
    }

    @Override
    public List<Deal> listByOrganization(Long organizationId) {
        Organization organization = organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        return dealRepository.findByOrganizationAndIsDeletedFalse(organization);
    }

    @Override
    public List<Deal> listByCategory(Long categoryId) {
        Category category = categoryRepository.findById(categoryId)
            .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        return dealRepository.findByDealCategoryAndIsDeletedFalse(category);
    }

    @Override
    @Transactional
    public Deal updateStage(Long id, DealDtos.UpdateStageRequest request) {
        log.info("updateStage called for deal {} with stageId: {}", id, request.stageId);
        
        Deal deal = get(id);
        Stage oldStage = deal.getStage();
        String oldStageName = oldStage != null ? oldStage.getName() : "null";
        log.info("Deal {} current stage: {} (ID: {})", id, oldStageName, oldStage != null ? oldStage.getId() : "null");
        
        Stage stage = stageRepository.findById(request.stageId)
                .orElseThrow(() -> new ResourceNotFoundException("Stage not found"));
        log.info("Moving deal {} to stage: {} (ID: {})", id, stage.getName(), stage.getId());
        
        deal.setStage(stage);
        // If stage is changed to "Contact Made", set venueAsked to true
        if ("Contact Made".equalsIgnoreCase(stage.getName())) {
            deal.setVenueAsked(true);
        }
        Deal saved = dealRepository.save(deal);
        
        log.info("Deal {} createdBy: {}", saved.getId(), saved.getCreatedBy());
        
        // Record stage change in history
        dealStageHistoryService.recordStageEntry(saved, stage);
        
        // Create activities ONLY if:
        // 1. Deal was created by BOT (backend handles it)
        // 2. Stage changed from "Lead In" to "Qualified"
        // Frontend will handle activity creation for USER-created deals
        boolean isBotCreated = saved.getCreatedBy() == CreatedByType.BOT;
        boolean isFromLeadIn = oldStage != null && "Lead In".equalsIgnoreCase(oldStage.getName());
        boolean isToQualified = "Qualified".equalsIgnoreCase(stage.getName());
        
        log.info("Activity creation check for deal {}: isBotCreated={}, isFromLeadIn={}, isToQualified={}", 
            saved.getId(), isBotCreated, isFromLeadIn, isToQualified);
        
        if (isBotCreated && isFromLeadIn && isToQualified) {
            log.info("All conditions met - calling createQualifiedStageActivities for deal {}", saved.getId());
            createQualifiedStageActivities(saved);
        } else {
            log.info("Skipping activity creation for deal {} - conditions not met", saved.getId());
        }

        maybeCreateBridesideDivertedCopy(saved, oldStage, stage);
        
        return saved;
    }

    @Override
    @Transactional
    public Deal markStatus(Long id, DealDtos.MarkStatusRequest request) {
        Deal deal = get(id);
        DealStatus status = request.status;
        
        // If marking as LOST, require lostReason
        if (status == DealStatus.LOST) {
            if (request.lostReason == null || request.lostReason.trim().isEmpty()) {
                throw new BadRequestException("lostReason is required when marking deal as LOST. Please select a reason from the list.");
            }
            DealLostReason lostReason = DealLostReason.fromString(request.lostReason);
            if (lostReason == null) {
                throw new BadRequestException("Invalid lostReason value: " + request.lostReason + 
                    ". Allowed values: Slot not opened, Not Interested, Date postponed, Not Available, Ghosted, Budget, Booked Someone else");
            }
            deal.setLostReason(lostReason);
            
            // If lost reason is Budget, require clientBudget
            if (lostReason == DealLostReason.BUDGET) {
                if (request.clientBudget == null || request.clientBudget.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BadRequestException("clientBudget is required and must be greater than 0 when lost reason is Budget. Please provide the client's budget amount.");
                }
                deal.setClientBudget(request.clientBudget);
            } else {
                // Clear clientBudget when lost reason is not Budget
                deal.setClientBudget(null);
            }
            
            // Optionally update value if provided
            if (request.value != null && request.value.compareTo(BigDecimal.ZERO) > 0) {
                deal.setValue(request.value);
            }
        } else {
            // Clear lost reason and clientBudget when status is not LOST
            deal.setLostReason(null);
            deal.setClientBudget(null);
        }
        
        // Handle WON status - require value and calculate commission
        if (status == DealStatus.WON) {
            // Get the deal value (from request or existing deal)
            BigDecimal dealValue = request.value != null ? request.value : deal.getValue();
            
            // Validate that value is provided
            if (dealValue == null || dealValue.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BadRequestException("Deal value is required when marking deal as WON. Please provide a value greater than 0.");
            }
            
            // Update deal value if provided in request
            if (request.value != null) {
                deal.setValue(request.value);
            }
            
            // Calculate commission based on deal source
            BigDecimal calculatedCommission = calculateCommissionFromDealSource(dealValue, deal.getDealSource());
            
            // Use provided commissionAmount if given, otherwise use calculated commission
            if (request.commissionAmount != null) {
                deal.setCommissionAmount(request.commissionAmount);
            } else {
                deal.setCommissionAmount(calculatedCommission);
            }
        }
        
        deal.setStatus(status);
        // Sync legacy 'won' column
        deal.setLegacyWon(status == DealStatus.WON);
        if (status == DealStatus.WON) {
            deal.setContractShared(Boolean.TRUE);
            deal.setWhatsappGroupCreated(Boolean.TRUE);
            deal.setWonAt(LocalDateTime.now());
            deal.setLostAt(null);
        } else if (status == DealStatus.LOST) {
            deal.setContractShared(null);
            deal.setWhatsappGroupCreated(null);
            deal.setLostAt(LocalDateTime.now());
            deal.setWonAt(null);
        } else {
            deal.setContractShared(null);
            deal.setWhatsappGroupCreated(null);
            deal.setWonAt(null);
            deal.setLostAt(null);
        }
        deal.setUpdatedAt(LocalDateTime.now());
        Deal saved = dealRepository.save(deal);
        syncGoogleCalendarEvent(saved);
        return saved;
    }

    /**
     * Calculates commission based on deal value and deal source.
     * - 10% for Direct, Reference, or Planner
     * - 15% for Divert
     */
    private BigDecimal calculateCommissionFromDealSource(BigDecimal dealValue, DealSource dealSource) {
        if (dealValue == null || dealValue.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        
        if (dealSource == null) {
            // Default to 10% if no source specified
            return dealValue.multiply(new BigDecimal("0.10"));
        }
        
        BigDecimal commissionRate;
        if (dealSource == DealSource.DIVERT) {
            commissionRate = new BigDecimal("0.15"); // 15% for Divert
        } else {
            // 10% for Direct, Reference, or Planner
            commissionRate = new BigDecimal("0.10");
        }
        
        return dealValue.multiply(commissionRate);
    }

    private Category resolveOrCreateCategory(Organization.OrganizationCategory orgCategory) {
        return categoryRepository.findByNameIgnoreCase(orgCategory.getDbValue())
                .orElseGet(() -> {
                    Category category = new Category();
                    category.setName(orgCategory.getDbValue());
                    return categoryRepository.save(category);
                });
    }

    private Category resolveSelectedCategory(DealDtos.CreateRequest request) {
        return resolveCategoryFromStrings(request.categoryId, request.category);
    }

    private Category resolveCategoryFromStrings(String categoryId, String category) {
        // Try to interpret categoryId first (may be numeric id or string code)
        if (categoryId != null && !categoryId.isBlank()) {
            String trimmed = categoryId.trim();
            try {
                Long id = Long.valueOf(trimmed);
                return categoryRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Category not found with id " + id));
            } catch (NumberFormatException ex) {
                Organization.OrganizationCategory orgCategory = Organization.OrganizationCategory.fromDbValue(trimmed);
                if (orgCategory == null) {
                    throw new BadRequestException("Unknown category value: " + trimmed);
                }
                return resolveOrCreateCategory(orgCategory);
            }
        }

        if (category != null && !category.isBlank()) {
            Organization.OrganizationCategory orgCategory = Organization.OrganizationCategory.fromDbValue(category);
            if (orgCategory == null) {
                throw new BadRequestException("Unknown category value: " + category);
            }
            return resolveOrCreateCategory(orgCategory);
        }

        return null;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        // Check if deal exists and is not already deleted
        Deal deal = dealRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Deal not found with id " + id));
        
        if (deal.getIsDeleted() != null && deal.getIsDeleted()) {
            throw new ResourceNotFoundException("Deal not found with id " + id);
        }
        
        // Find all deals that reference this deal (diverted deals) - including soft-deleted ones
        // We need to clear references from both deleted and non-deleted deals to prevent foreign key constraint violations
        List<Deal> referencingDeals = dealRepository.findByReferencedDeal(deal);
        
        // Clear the reference for all deals that reference this one (both deleted and non-deleted)
        // This prevents foreign key constraint violations
        for (Deal referencingDeal : referencingDeals) {
            referencingDeal.setReferencedDeal(null);
            // Also clear the referenced pipeline since the original deal is being deleted
            referencingDeal.setReferencedPipeline(null);
            dealRepository.save(referencingDeal);
        }
        
        // Soft delete: set is_deleted to true instead of hard delete
        removeGoogleCalendarEvent(deal);
        deal.setIsDeleted(Boolean.TRUE);
        dealRepository.save(deal);
    }

    private BigDecimal calculateCommission(BigDecimal value, Source source) {
        if (value == null) return BigDecimal.ZERO;
        if (source.getFixedCommissionAmount() != null) return source.getFixedCommissionAmount();
        Integer pct = source.getCommissionPercentage() == null ? 0 : source.getCommissionPercentage();
        return value.multiply(BigDecimal.valueOf(pct)).divide(BigDecimal.valueOf(100));
    }

    /**
     * Gets the original pipeline from which a deal was diverted.
     * If the deal is already diverted, traverses up the chain to find the original pipeline.
     * If the deal is not diverted, returns its current pipeline.
     */
    private Pipeline getOriginalPipeline(Deal deal) {
        // If the deal has a referenced pipeline, it means it was diverted
        // Traverse up the chain to find the original pipeline
        if (deal.getReferencedPipeline() != null) {
            return deal.getReferencedPipeline();
        }
        
        // If the deal is not diverted, return its current pipeline
        return deal.getPipeline();
    }

    /**
     * Gets the source pipeline (initial pipeline) from which a deal was first created/diverted.
     * Traverses up the chain to find the source pipeline.
     */
    private Pipeline getSourcePipeline(Deal deal) {
        // If the deal has a source pipeline, return it
        if (deal.getSourcePipeline() != null) {
            return deal.getSourcePipeline();
        }
        
        // If not set, check if it's a diverted deal and get source from referenced deal
        if (deal.getReferencedDeal() != null) {
            return getSourcePipeline(deal.getReferencedDeal());
        }
        
        // If not diverted, return current pipeline as source
        return deal.getPipeline();
    }

    /**
     * Gets the pipeline history as a list of pipeline IDs.
     */
    private List<Long> getPipelineHistory(Deal deal) {
        List<Long> history = new ArrayList<>();
        
        // Skip deleted deals
        if (deal.getIsDeleted() != null && deal.getIsDeleted()) {
            return history;
        }
        
        // Get history from referenced deal if it exists and is not deleted
        if (deal.getReferencedDeal() != null) {
            Deal referencedDeal = deal.getReferencedDeal();
            if (referencedDeal.getIsDeleted() == null || !referencedDeal.getIsDeleted()) {
                history = getPipelineHistory(referencedDeal);
            }
        }
        
        // Parse JSON history if exists
        if (deal.getPipelineHistory() != null && !deal.getPipelineHistory().isEmpty()) {
            try {
                List<Long> jsonHistory = objectMapper.readValue(
                    deal.getPipelineHistory(),
                    new TypeReference<List<Long>>() {}
                );
                // Merge with existing history, avoiding duplicates
                for (Long pipelineId : jsonHistory) {
                    if (!history.contains(pipelineId)) {
                        history.add(pipelineId);
                    }
                }
            } catch (Exception e) {
                // If JSON parsing fails, continue with existing history
            }
        }
        
        return history;
    }

    /**
     * Converts a list of pipeline IDs to JSON string.
     */
    private String pipelineHistoryToJson(List<Long> pipelineIds) {
        try {
            return objectMapper.writeValueAsString(pipelineIds);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * When a deal leaves Lead In / Qualified for Contact Made or a later stage in a non-Brideside pipeline,
     * creates one mirrored deal in The Bride Side (TBS) pipeline at Qualified, unless a duplicate already exists.
     */
    private void maybeCreateBridesideDivertedCopy(Deal sourceDealAfterSave, Stage oldStage, Stage newStage) {
        if (tbsBridesidePipelineId == null || tbsBridesideOrganizationId == null) {
            return;
        }
        if (sourceDealAfterSave == null || newStage == null) {
            return;
        }
        if (sourceDealAfterSave.getIsDeleted() != null && sourceDealAfterSave.getIsDeleted()) {
            return;
        }
        Pipeline dealPipeline = sourceDealAfterSave.getPipeline();
        if (dealPipeline == null || dealPipeline.getId() == null) {
            return;
        }
        if (dealPipeline.getId().equals(tbsBridesidePipelineId)) {
            return;
        }
        if (oldStage == null) {
            return;
        }
        String oldName = oldStage.getName();
        if (oldName == null) {
            return;
        }
        boolean fromLeadInOrQualified = "Lead In".equalsIgnoreCase(oldName) || "Qualified".equalsIgnoreCase(oldName);
        if (!fromLeadInOrQualified) {
            return;
        }
        Pipeline newStagePipeline = newStage.getPipeline();
        if (newStagePipeline == null || newStagePipeline.getId() == null
            || !newStagePipeline.getId().equals(dealPipeline.getId())) {
            log.debug("Skipping Brideside auto-divert: new stage pipeline does not match deal pipeline for deal {}",
                sourceDealAfterSave.getId());
            return;
        }
        if (!isContactMadeOrLaterStage(dealPipeline, newStage)) {
            return;
        }

        Pipeline tbsPipeline = pipelineRepository.findById(tbsBridesidePipelineId).orElse(null);
        if (tbsPipeline == null) {
            log.warn("Brideside auto-divert: TBS pipeline id {} not found", tbsBridesidePipelineId);
            return;
        }
        Organization tbsOrganization = organizationRepository.findById(tbsBridesideOrganizationId).orElse(null);
        if (tbsOrganization == null) {
            log.warn("Brideside auto-divert: TBS organization id {} not found", tbsBridesideOrganizationId);
            return;
        }

        if (dealRepository.existsByReferencedDealAndPipeline(sourceDealAfterSave, tbsPipeline)) {
            log.debug("Brideside auto-divert: deal {} already has a copy in TBS pipeline, skipping",
                sourceDealAfterSave.getId());
            return;
        }

        Long personId = sourceDealAfterSave.getPersonId();
        if (personId != null) {
            long dupByPerson = dealRepository.countNonDeletedInPipelineWithPersonId(
                    tbsBridesidePipelineId, personId);
            if (dupByPerson > 0) {
                log.debug("Brideside auto-divert: pipeline {} already has a non-deleted deal for personId {}, "
                        + "skipping copy for source deal {}",
                    tbsBridesidePipelineId, personId, sourceDealAfterSave.getId());
                return;
            }
        }

        Person sourcePerson = null;
        if (personId != null) {
            sourcePerson = personRepository.findById(personId).orElse(null);
        }
        if (sourcePerson == null) {
            sourcePerson = sourceDealAfterSave.getPerson();
        }
        if (sourcePerson != null) {
            String ig = sourcePerson.getInstagramId();
            if (ig != null && !ig.trim().isEmpty()) {
                long dupByIg = dealRepository.countNonDeletedInPipelineWithPersonInstagramId(
                    tbsBridesidePipelineId, ig.trim());
                if (dupByIg > 0) {
                    log.debug("Brideside auto-divert: pipeline {} already has a deal for instagram match, skipping copy for source deal {}",
                        tbsBridesidePipelineId, sourceDealAfterSave.getId());
                    return;
                }
            }
        }

        List<Stage> tbsStages = stageRepository.findByPipelineAndActiveTrueOrderByOrderIndexAsc(tbsPipeline);
        Optional<Stage> qualifiedOnTbs = tbsStages.stream()
            .filter(s -> "Qualified".equalsIgnoreCase(s.getName()))
            .findFirst();
        if (qualifiedOnTbs.isEmpty()) {
            log.warn("Brideside auto-divert: no Active Qualified stage on TBS pipeline {}", tbsBridesidePipelineId);
            return;
        }

        Deal copy = buildBridesideDivertedDeal(sourceDealAfterSave, sourcePerson, tbsPipeline, qualifiedOnTbs.get(), tbsOrganization);
        Deal savedCopy = dealRepository.save(copy);

        dealStageHistoryService.recordStageEntry(savedCopy, qualifiedOnTbs.get());
        createQualifiedStageActivities(savedCopy);
        syncGoogleCalendarEvent(savedCopy);
        log.info("Brideside auto-divert: created deal {} from source {}", savedCopy.getId(), sourceDealAfterSave.getId());
    }

    private boolean isContactMadeOrLaterStage(Pipeline pipeline, Stage newStage) {
        List<Stage> stages = stageRepository.findByPipelineAndActiveTrueOrderByOrderIndexAsc(pipeline);
        Optional<Stage> contactMade = stages.stream()
            .filter(s -> "Contact Made".equalsIgnoreCase(s.getName()))
            .findFirst();
        if (contactMade.isEmpty()) {
            log.warn("isContactMadeOrLaterStage: pipeline {} has no Active Contact Made stage", pipeline.getId());
            return false;
        }
        Integer cmOrder = contactMade.get().getOrderIndex();
        Integer newOrder = newStage.getOrderIndex();
        if (cmOrder == null || newOrder == null) {
            return "Contact Made".equalsIgnoreCase(newStage.getName());
        }
        return newOrder >= cmOrder;
    }

    private Deal buildBridesideDivertedDeal(Deal source, Person resolvedPerson, Pipeline tbsPipeline, Stage qualifiedStage,
                                            Organization tbsOrganization) {
        Deal deal = new Deal();
        deal.setName(buildBridesideDivertedDealName(source.getName()));
        deal.setValue(source.getValue() != null ? source.getValue() : BigDecimal.ZERO);
        deal.setPerson(resolvedPerson);
        if (resolvedPerson != null) {
            String phone = resolvedPerson.getPhone();
            if (phone != null) {
                deal.setContactNumber(phone);
            }
        }
        if (deal.getContactNumber() == null && source.getContactNumber() != null) {
            deal.setContactNumber(source.getContactNumber());
        }
        if (deal.getContactNumber() == null) {
            deal.setContactNumber("");
        }
        deal.setPipeline(tbsPipeline);
        deal.setStage(qualifiedStage);
        deal.setOrganization(tbsOrganization);
        // Owner = team manager of the target (Brideside) pipeline; fall back to the org owner.
        User targetPipelineManager = (tbsPipeline.getTeam() != null) ? tbsPipeline.getTeam().getManager() : null;
        if (targetPipelineManager != null) {
            deal.setOwner(targetPipelineManager);
        } else {
            User orgOwner = tbsOrganization.getOwner();
            if (orgOwner != null) {
                deal.setOwner(orgOwner);
            }
        }
        deal.setSource(source.getSource());
        // deals.category_id — Brideside pipeline copy uses configured category (default 4), not the source deal's category
        if (tbsBridesideDealCategoryId != null) {
            deal.setDealCategory(categoryRepository.getReferenceById(tbsBridesideDealCategoryId));
        }
        deal.setEventType(source.getEventType());
        deal.setVenue(source.getVenue());
        deal.setPhoneNumber(source.getPhoneNumber());
        deal.setCity(source.getCity());
        deal.setNotes(source.getNotes());
        deal.setEventDate(source.getEventDate());
        deal.setEventDates(source.getEventDates());
        deal.setUserName(source.getUserName() != null ? source.getUserName() : "");
        deal.setStatus(DealStatus.IN_PROGRESS);
        deal.setLegacyWon(false);
        deal.setWonAt(null);
        deal.setLostAt(null);
        deal.setContractShared(null);
        deal.setWhatsappGroupCreated(null);
        deal.setLostReason(null);
        deal.setClientBudget(null);
        deal.setFinalThankYouSent(source.getFinalThankYouSent());
        deal.setEventDateAsked(source.getEventDateAsked());
        deal.setContactNumberAsked(source.getContactNumberAsked());
        deal.setVenueAsked(source.getVenueAsked());
        if (source.getCommissionAmount() != null) {
            deal.setCommissionAmount(source.getCommissionAmount());
        } else if (deal.getSource() != null) {
            deal.setCommissionAmount(calculateCommission(deal.getValue(), deal.getSource()));
        } else {
            deal.setCommissionAmount(BigDecimal.ZERO);
        }
        deal.setDealSource(DealSource.DIVERT);
        deal.setDealSubSource(null);
        deal.setIsDiverted(Boolean.TRUE);
        deal.setReenteredViaDirectMessage(Boolean.FALSE);
        deal.setIsDeleted(Boolean.FALSE);
        deal.setReferencedDeal(source);
        Pipeline originalPipeline = getOriginalPipeline(source);
        if (originalPipeline != null) {
            deal.setReferencedPipeline(originalPipeline);
        }
        Pipeline sourcePipeline = getSourcePipeline(source);
        if (sourcePipeline != null) {
            deal.setSourcePipeline(sourcePipeline);
        }
        List<Long> pipelineHistory = getPipelineHistory(source);
        if (source.getPipeline() != null) {
            Long currentPipelineId = source.getPipeline().getId();
            if (!pipelineHistory.contains(currentPipelineId)) {
                pipelineHistory.add(currentPipelineId);
            }
        }
        deal.setPipelineHistory(pipelineHistoryToJson(pipelineHistory));
        deal.setCreatedBy(CreatedByType.BOT);
        deal.setCreatedByUserId(null);
        deal.setCreatedByName(null);
        deal.setGoogleCalendarEventId(null);
        deal.setGoogleCalendarEventIds(null);
        return deal;
    }

    private static String buildBridesideDivertedDealName(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return "(Diverted)";
        }
        String base = sourceName.trim();
        if (base.endsWith(" (Diverted)")) {
            return base;
        }
        return base + " (Diverted)";
    }

    @Override
    public List<PipelineDtos.PipelineResponse> getAvailablePipelinesForDiversion(Long dealId) {
        // Get the deal (could be original or already diverted)
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal not found with id " + dealId));
        
        // Check if deal is deleted
        if (deal.getIsDeleted() != null && deal.getIsDeleted()) {
            throw new ResourceNotFoundException("Deal not found with id " + dealId);
        }
        
        // Get pipeline history - all pipelines this deal has been in
        List<Long> pipelineHistory = getPipelineHistory(deal);
        
        // Also add current pipeline to history if not already there
        if (deal.getPipeline() != null) {
            Long currentPipelineId = deal.getPipeline().getId();
            if (!pipelineHistory.contains(currentPipelineId)) {
                pipelineHistory.add(currentPipelineId);
            }
        }
        
        // Get all active pipelines
        List<Pipeline> allPipelines = pipelineRepository.findByDeletedFalseOrderByNameAsc();
        
        // Get all deals in the diversion chain (current deal + all deals it references)
        List<Deal> dealsInChain = getAllDealsInChain(deal);
        
        // Filter out:
        // 1. Pipelines where a diverted deal already exists for ANY deal in the chain
        // 2. All pipelines in the history (pipelines the deal has already been in)
        // 3. Pipelines where any deal diverted FROM this deal (or deals in chain) has been diverted to
        return allPipelines.stream()
                .filter(pipeline -> {
                    // Exclude all pipelines in history (prevents diverting back to any previous pipeline)
                    if (pipelineHistory.contains(pipeline.getId())) {
                        return false;
                    }
                    
                    // Exclude if ANY deal in the diversion chain has already been diverted to this pipeline (only non-deleted deals)
                    for (Deal dealInChain : dealsInChain) {
                        // Check if there's a non-deleted diverted deal
                        List<Deal> divertedDeals = dealRepository.findByReferencedDealAndIsDeletedFalse(dealInChain);
                        for (Deal divertedDeal : divertedDeals) {
                            if (divertedDeal.getPipeline() != null && divertedDeal.getPipeline().getId().equals(pipeline.getId())) {
                                return false;
                            }
                        }
                    }
                    
                    // Exclude if any deal that was diverted FROM deals in the chain has been diverted to this pipeline
                    // This handles the case: Pipeline1 → Pipeline2 → Pipeline3
                    // When checking from Pipeline1, we need to exclude Pipeline3 because Pipeline2 (diverted from Pipeline1) was diverted to Pipeline3
                    for (Deal dealInChain : dealsInChain) {
                        // Get all deals that were diverted FROM this deal (only non-deleted)
                        List<Deal> divertedDeals = dealRepository.findByReferencedDealAndIsDeletedFalse(dealInChain);
                        // Check if any of those diverted deals have been diverted to the target pipeline (only non-deleted)
                        for (Deal divertedDeal : divertedDeals) {
                            List<Deal> furtherDivertedDeals = dealRepository.findByReferencedDealAndIsDeletedFalse(divertedDeal);
                            for (Deal furtherDivertedDeal : furtherDivertedDeals) {
                                if (furtherDivertedDeal.getPipeline() != null && furtherDivertedDeal.getPipeline().getId().equals(pipeline.getId())) {
                                    return false;
                                }
                            }
                        }
                    }
                    
                    return true;
                })
                .map(pipeline -> PipelineMapper.toPipelineResponse(pipeline, Collections.emptyList(), false))
                .collect(Collectors.toList());
    }
    
    /**
     * Gets all deals in the diversion chain (current deal + all deals it references up to the original).
     * Returns a list starting from the current deal and going up to the original deal.
     */
    private List<Deal> getAllDealsInChain(Deal deal) {
        List<Deal> chain = new ArrayList<>();
        Deal current = deal;
        
        // Add current deal and traverse up the chain (only non-deleted deals)
        while (current != null) {
            // Only add non-deleted deals to the chain
            if (current.getIsDeleted() == null || !current.getIsDeleted()) {
                chain.add(current);
            }
            current = current.getReferencedDeal();
        }
        
        return chain;
    }

    private void syncGoogleCalendarEvent(Deal deal) {
        if (googleCalendarService == null || deal == null) {
            return;
        }
        try {
            boolean hasCalendar = deal.getOrganization() != null
                    && StringUtils.hasText(deal.getOrganization().getGoogleCalendarId());
            LocalDate firstEventDate = getFirstEventDate(deal);
            if (!hasCalendar || firstEventDate == null) {
                // Remove all calendar events if no dates or no calendar
                if (StringUtils.hasText(deal.getGoogleCalendarEventIds()) || StringUtils.hasText(deal.getGoogleCalendarEventId())) {
                    removeGoogleCalendarEvent(deal);
                }
                return;
            }
            
            // Use new method to sync multiple events
            googleCalendarService.upsertDealEvents(deal)
                    .ifPresent(eventIdsMap -> {
                        String eventIdsJson = eventIdsMapToJson(eventIdsMap);
                        if (!eventIdsJson.equals(deal.getGoogleCalendarEventIds())) {
                            deal.setGoogleCalendarEventIds(eventIdsJson);
                            // Also set legacy eventId to first event for backward compatibility
                            if (!eventIdsMap.isEmpty()) {
                                deal.setGoogleCalendarEventId(eventIdsMap.values().iterator().next());
                            }
                            dealRepository.save(deal);
                        }
                    });
        } catch (Exception ex) {
            log.warn("Google Calendar sync skipped for deal {}: {}", deal.getId(), ex.getMessage(), ex);
        }
    }

    private void removeGoogleCalendarEvent(Deal deal) {
        if (googleCalendarService == null || deal == null) {
            return;
        }
        try {
            googleCalendarService.deleteDealEvents(deal);
        } catch (Exception ex) {
            log.warn("Failed to remove Google Calendar events for deal {}: {}", deal.getId(), ex.getMessage(), ex);
        } finally {
            deal.setGoogleCalendarEventIds(null);
            deal.setGoogleCalendarEventId(null);
            dealRepository.save(deal);
        }
    }
    
    /**
     * Converts a map of date strings to event IDs to JSON string.
     */
    private String eventIdsMapToJson(Map<String, String> eventIdsMap) {
        if (eventIdsMap == null || eventIdsMap.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(eventIdsMap);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * When a deal's owner changes, update incomplete activities for this deal to match the new owner.
     */
    private void reassignIncompleteActivitiesToDealOwner(Deal deal) {
        if (deal == null || deal.getId() == null) {
            return;
        }
        User owner = deal.getOwner();
        if (owner == null || owner.getId() == null) {
            return;
        }
        List<Activity> activities = activityRepository.findByDealIdAndDoneFalse(deal.getId());
        if (activities.isEmpty()) {
            return;
        }
        String displayName = (owner.getFirstName() != null ? owner.getFirstName() : "") + " "
                + (owner.getLastName() != null ? owner.getLastName() : "");
        displayName = displayName.trim();
        if (displayName.isEmpty() && owner.getEmail() != null) {
            displayName = owner.getEmail();
        }
        // activities.assigned_user is varchar(255) per DDL
        displayName = truncateActivityAssignedUser(displayName);
        Long ownerId = owner.getId();
        int updated = 0;
        for (Activity a : activities) {
            if (a.getStatus() == Activity.ActivityStatus.COMPLETED) {
                continue;
            }
            if (a.getCompletedAt() != null) {
                continue;
            }
            a.setAssignedUserId(ownerId);
            a.setAssignedUser(displayName);
            // Keep user_id in sync with assignee (same FK target as assigned_user_id in DDL)
            a.setUserId(ownerId);
            activityRepository.save(a);
            updated++;
        }
        if (updated > 0) {
            log.info("Reassigned {} incomplete activities to deal owner {} for deal {}", updated, ownerId, deal.getId());
        }
    }

    /** Matches activities.assigned_user varchar(255). */
    private static String truncateActivityAssignedUser(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 255 ? s : s.substring(0, 255);
    }

    private void resolveAndApplyInitialStage(Deal deal, Long requestedStageId, String requestPhoneNumber) {
        Pipeline pipeline = deal.getPipeline();
        if (pipeline == null) {
            return;
        }

        boolean hasPhone = hasPhoneNumber(
                deal.getContactNumber(),
                deal.getPhoneNumber(),
                requestPhoneNumber,
                deal.getPerson() != null ? deal.getPerson().getPhone() : null);

        if (deal.getCreatedBy() == CreatedByType.BOT) {
            Stage resolved = resolveStageForPipeline(pipeline, hasPhone);
            Stage requested = requestedStageId != null
                    ? stageRepository.findById(requestedStageId).orElse(null)
                    : deal.getStage();
            if (requested != null && stageBelongsToPipeline(requested, pipeline) && requested.getId().equals(resolved.getId())) {
                deal.setStage(requested);
            } else {
                if (requestedStageId != null && (requested == null || !stageBelongsToPipeline(requested, pipeline))) {
                    log.warn("BOT deal: stage {} is invalid for pipeline {} — using {} (hasPhone={})",
                            requestedStageId, pipeline.getId(), resolved.getName(), hasPhone);
                }
                deal.setStage(resolved);
            }
            return;
        }

        if (deal.getStage() == null) {
            deal.setStage(resolveStageForPipeline(pipeline, hasPhone));
            return;
        }

        validateStageBelongsToPipeline(deal.getStage(), pipeline);
    }

    private Stage resolveStageForPipeline(Pipeline pipeline, boolean hasPhone) {
        String stageName = hasPhone ? "Qualified" : "Lead In";
        return stageRepository.findFirstByPipelineAndNameIgnoreCaseAndActiveTrue(pipeline, stageName)
                .orElseThrow(() -> new BadRequestException(
                        "Pipeline " + pipeline.getId() + " has no active '" + stageName + "' stage"));
    }

    private boolean stageBelongsToPipeline(Stage stage, Pipeline pipeline) {
        if (stage == null || pipeline == null || stage.getPipeline() == null) {
            return false;
        }
        return pipeline.getId().equals(stage.getPipeline().getId());
    }

    private void validateStageBelongsToPipeline(Stage stage, Pipeline pipeline) {
        if (stage == null || pipeline == null) {
            return;
        }
        if (!stageBelongsToPipeline(stage, pipeline)) {
            throw new BadRequestException(
                    "Stage " + stage.getId() + " does not belong to pipeline " + pipeline.getId());
        }
    }

    private boolean hasPhoneNumber(String... values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves a BOT-created deal from Lead In to Qualified when a phone number is present.
     *
     * @return true if the stage was changed
     */
    private boolean maybePromoteBotDealOnPhoneReceived(Deal deal, Stage oldStage) {
        if (deal.getCreatedBy() != CreatedByType.BOT || deal.getPipeline() == null) {
            return false;
        }
        if (!hasPhoneNumber(deal.getContactNumber(), deal.getPhoneNumber(),
                deal.getPerson() != null ? deal.getPerson().getPhone() : null)) {
            return false;
        }
        if (oldStage != null && !"Lead In".equalsIgnoreCase(oldStage.getName())) {
            return false;
        }

        Stage qualified = resolveStageForPipeline(deal.getPipeline(), true);
        if (deal.getStage() != null && qualified.getId().equals(deal.getStage().getId())) {
            return false;
        }
        deal.setStage(qualified);
        if (deal.getContactNumber() == null || deal.getContactNumber().isBlank()) {
            String phone = deal.getPerson() != null ? deal.getPerson().getPhone() : null;
            if (phone == null || phone.isBlank()) {
                phone = deal.getPhoneNumber();
            }
            if (phone != null && !phone.isBlank()) {
                deal.setContactNumber(phone.trim());
            }
        }
        return true;
    }

    private void resolveDealOwnerFromPersonOrOrganization(Deal deal) {
        if (deal.getPerson() != null && deal.getPerson().getOwner() != null) {
            deal.setOwner(deal.getPerson().getOwner());
            return;
        }
        if (deal.getOrganization() != null && deal.getOrganization().getOwner() != null) {
            deal.setOwner(deal.getOrganization().getOwner());
        }
    }

    /**
     * Returns the team manager for the deal's pipeline, or null if the pipeline
     * has no team or the team has no manager.
     */
    private User resolvePipelineTeamManager(Deal deal) {
        if (deal == null || deal.getPipeline() == null) {
            return null;
        }
        var team = deal.getPipeline().getTeam();
        if (team == null) {
            return null;
        }
        return team.getManager();
    }
    
    /**
     * Creates activities when a deal moves from "Lead In" to "Qualified" stage.
     * Creates "Make first call" and "Send Quotes" activities assigned to the team manager.
     * This method should ONLY be called for BOT-created deals.
     * Frontend handles activity creation for USER-created deals.
     */
    private void createQualifiedStageActivities(Deal deal) {
        // Double-check: Only create for BOT-created deals
        if (deal.getCreatedBy() != CreatedByType.BOT) {
            log.warn("Skipping activity creation for deal {} - not created by BOT", deal.getId());
            return;
        }
        try {
            log.info("Creating activities for Qualified stage deal {} (BOT-created)", deal.getId());
            
            // Reload deal from database to ensure we have fresh data with all relationships
            Deal freshDeal = dealRepository.findById(deal.getId())
                .orElse(null);
            if (freshDeal == null) {
                log.error("Cannot create activities: Deal {} not found in database", deal.getId());
                return;
            }
            
            // 1. Resolve the best available phone number for the activity.
            // Bot flows can persist the captured phone on the deal before the linked person is updated.
            Person person = freshDeal.getPerson();
            String activityPhone = null;
            if (freshDeal.getPhoneNumber() != null && !freshDeal.getPhoneNumber().trim().isEmpty()) {
                activityPhone = freshDeal.getPhoneNumber().trim();
                log.debug("Deal {} will use deal phoneNumber {}", freshDeal.getId(), activityPhone);
            } else if (person != null && person.getPhone() != null && !person.getPhone().trim().isEmpty()) {
                activityPhone = person.getPhone().trim();
                log.debug("Deal {} will use person {} phone {}", freshDeal.getId(), person.getId(), activityPhone);
            } else if (freshDeal.getContactNumber() != null && !freshDeal.getContactNumber().trim().isEmpty()) {
                activityPhone = freshDeal.getContactNumber().trim();
                log.debug("Deal {} will use deal contact number {}", freshDeal.getId(), activityPhone);
            }

            if (activityPhone == null) {
                log.warn("Skipping activity creation for deal {}: no phone number on deal.phoneNumber, person.phone, or deal.contactNumber", freshDeal.getId());
                return;
            }
            
            // 2. Get team manager using direct SQL query following the exact path:
            // deals.pipeline_id -> pipelines.team_id -> teams.manager_id -> users
            Long assignedUserId = null;
            String assignedUserName = null;
            
            try {
                // Step 1: Get pipeline_id from deal
                Long pipelineId = freshDeal.getPipeline() != null ? freshDeal.getPipeline().getId() : null;
                if (pipelineId == null) {
                    log.error("Cannot create activities for Qualified stage: Deal {} has no pipeline_id", freshDeal.getId());
                    return;
                }
                log.info("Deal {} has pipeline_id: {}", freshDeal.getId(), pipelineId);
                
                // Step 2: Get team_id from pipeline
                String teamIdQuery = "SELECT team_id FROM pipelines WHERE id = ?";
                Long teamId = null;
                try {
                    teamId = jdbcTemplate.queryForObject(teamIdQuery, Long.class, pipelineId);
                } catch (Exception e) {
                    log.error("Cannot create activities for Qualified stage: Pipeline {} has no team_id or query failed: {}", 
                        pipelineId, e.getMessage());
                    return;
                }
                if (teamId == null) {
                    log.error("Cannot create activities for Qualified stage: Pipeline {} has no team_id", pipelineId);
                    return;
                }
                log.info("Pipeline {} has team_id: {}", pipelineId, teamId);
                
                // Step 3: Get manager_id from team
                String managerIdQuery = "SELECT manager_id FROM teams WHERE id = ?";
                Long managerId = null;
                try {
                    managerId = jdbcTemplate.queryForObject(managerIdQuery, Long.class, teamId);
                } catch (Exception e) {
                    log.error("Cannot create activities for Qualified stage: Team {} has no manager_id or query failed: {}", 
                        teamId, e.getMessage());
                    return;
                }
                if (managerId == null) {
                    log.error("Cannot create activities for Qualified stage: Team {} has no manager_id", teamId);
                    return;
                }
                log.info("Team {} has manager_id: {}", teamId, managerId);
                
                // Step 4: Get user details from users table
                String userQuery = "SELECT id, first_name, last_name FROM users WHERE id = ?";
                Map<String, Object> userRow = null;
                try {
                    userRow = jdbcTemplate.queryForMap(userQuery, managerId);
                } catch (Exception e) {
                    log.error("Cannot create activities for Qualified stage: User {} not found or query failed: {}", 
                        managerId, e.getMessage());
                    return;
                }
                
                if (userRow == null || userRow.isEmpty()) {
                    log.error("Cannot create activities for Qualified stage: User {} not found", managerId);
                    return;
                }
                
                Long userId = ((Number) userRow.get("id")).longValue();
                String firstName = (String) userRow.get("first_name");
                String lastName = (String) userRow.get("last_name");
                
                assignedUserId = userId;
                assignedUserName = (firstName != null ? firstName : "") + " " + 
                                  (lastName != null ? lastName : "");
                assignedUserName = assignedUserName.trim();
                
                if (assignedUserId == null) {
                    log.error("Team manager ID is null for deal {} - cannot assign activities", freshDeal.getId());
                    return;
                }
                if (assignedUserName == null || assignedUserName.trim().isEmpty()) {
                    log.error("Team manager name is null or empty for deal {} - cannot assign activities", freshDeal.getId());
                    return;
                }
                
                log.info("Found team manager for deal {}: {} (ID: {}) - Activities will be assigned to this user", 
                    freshDeal.getId(), assignedUserName, assignedUserId);
                    
            } catch (Exception e) {
                log.error("Error getting team manager for deal {}: {}", freshDeal.getId(), e.getMessage(), e);
                // Don't return yet - try fallback options
            }
            
            // FALLBACK 1: Try to use person owner if team manager not found
            if (assignedUserId == null && freshDeal.getPerson() != null && freshDeal.getPerson().getOwner() != null) {
                try {
                    User personOwner = freshDeal.getPerson().getOwner();
                    assignedUserId = personOwner.getId();
                    assignedUserName = (personOwner.getFirstName() != null ? personOwner.getFirstName() : "") + " " + 
                                    (personOwner.getLastName() != null ? personOwner.getLastName() : "");
                    assignedUserName = assignedUserName.trim();
                    
                    log.info("Using person owner as fallback for deal {}: {} (ID: {})", 
                        freshDeal.getId(), assignedUserName, assignedUserId);
                    
                } catch (Exception e) {
                    log.warn("Could not get person owner for deal {}: {}", freshDeal.getId(), e.getMessage());
                }
            }
            
            // FALLBACK 2: Try to use organization owner if deal owner not found
            if (assignedUserId == null && freshDeal.getOrganization() != null && freshDeal.getOrganization().getId() != null) {
                try {
                    // First get organization owner_id
                    String orgOwnerQuery = "SELECT owner_id FROM organizations WHERE id = ?";
                    Long orgOwnerId = jdbcTemplate.queryForObject(orgOwnerQuery, Long.class, freshDeal.getOrganization().getId());
                    
                    if (orgOwnerId != null) {
                        String orgOwnerUserQuery = "SELECT id, first_name, last_name FROM users WHERE id = ?";
                        Map<String, Object> orgOwnerRow = jdbcTemplate.queryForMap(orgOwnerUserQuery, orgOwnerId);
                        
                        if (orgOwnerRow != null && !orgOwnerRow.isEmpty()) {
                            Long ownerId = ((Number) orgOwnerRow.get("id")).longValue();
                            String ownerFirstName = (String) orgOwnerRow.get("first_name");
                            String ownerLastName = (String) orgOwnerRow.get("last_name");
                            
                            assignedUserId = ownerId;
                            assignedUserName = (ownerFirstName != null ? ownerFirstName : "") + " " + 
                                            (ownerLastName != null ? ownerLastName : "");
                            assignedUserName = assignedUserName.trim();
                            
                            log.info("Using organization owner as fallback for deal {}: {} (ID: {})", 
                                freshDeal.getId(), assignedUserName, assignedUserId);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not get organization owner for deal {}: {}", freshDeal.getId(), e.getMessage());
                }
            }
            
            // CRITICAL: If still no user assigned, log error and DO NOT create activities
            if (assignedUserId == null) {
                log.error("CRITICAL: Cannot assign activities for deal {} - no team manager, person owner, or organization owner found. Pipeline: {}, Team: {}, Person Owner: {}, Organization: {}", 
                    freshDeal.getId(), 
                    freshDeal.getPipeline() != null ? freshDeal.getPipeline().getId() : "null",
                    freshDeal.getPipeline() != null && freshDeal.getPipeline().getTeam() != null ? freshDeal.getPipeline().getTeam().getId() : "null",
                    freshDeal.getPerson() != null && freshDeal.getPerson().getOwner() != null ? freshDeal.getPerson().getOwner().getId() : "null",
                    freshDeal.getOrganization() != null ? freshDeal.getOrganization().getId() : "null");
                return; // Don't create activities without assignedUserId
            }
            
            // 3. Get organization
            Organization organization = freshDeal.getOrganization();
            Long organizationId = organization != null ? organization.getId() : null;
            String organizationName = organization != null ? organization.getName() : null;
            
            // 4. Check for existing activities to prevent duplicates
            List<Activity> existingActivities = activityRepository.findAll().stream()
                .filter(a -> freshDeal.getId().equals(a.getDealId()))
                .collect(Collectors.toList());
            
            boolean hasMakeFirstCall = existingActivities.stream()
                .anyMatch(a -> "Make first call".equals(a.getSubject()));
            boolean hasSendQuotes = existingActivities.stream()
                .anyMatch(a -> "Send Quotes".equals(a.getSubject()));
            
            if (hasMakeFirstCall && hasSendQuotes) {
                log.debug("Activities already exist for Qualified stage deal {}, skipping creation", freshDeal.getId());
                return;
            }

            assignedUserName = truncateActivityAssignedUser(assignedUserName);
            
            // 5. Calculate today's date in DD/MM/YYYY format
            LocalDate today = LocalDate.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            String todayStr = today.format(formatter);
            
            // 6. Create "Make first call" activity
            if (!hasMakeFirstCall) {
                Activity makeFirstCall = new Activity();
                makeFirstCall.setSubject("Make first call");
                makeFirstCall.setCategory(Activity.ActivityCategory.CALL);
                makeFirstCall.setStatus(Activity.ActivityStatus.OPEN);
                makeFirstCall.setDealId(freshDeal.getId());
                makeFirstCall.setPersonId(person != null ? person.getId() : null);
                makeFirstCall.setOrganizationId(organizationId);
                makeFirstCall.setOrganization(organizationName);
                makeFirstCall.setAssignedUser(assignedUserName);
                makeFirstCall.setAssignedUserId(assignedUserId);
                makeFirstCall.setUserId(assignedUserId);
                makeFirstCall.setDate(todayStr);
                makeFirstCall.setDone(false);
                makeFirstCall.setDealName(freshDeal.getName());
                makeFirstCall.setPhone(activityPhone);
                
                // Verify values before saving
                if (makeFirstCall.getAssignedUserId() == null || makeFirstCall.getAssignedUser() == null) {
                    log.error("ERROR: Activity assignment values are null before saving! assignedUserId={}, assignedUser='{}'", 
                        makeFirstCall.getAssignedUserId(), makeFirstCall.getAssignedUser());
                    return;
                }
                
                log.info("Creating 'Make first call' activity for deal {} with assignedUserId={}, assignedUser='{}'", 
                    freshDeal.getId(), makeFirstCall.getAssignedUserId(), makeFirstCall.getAssignedUser());
                
                Activity saved = activityRepository.save(makeFirstCall);
                
                // Verify after saving
                log.info("Created 'Make first call' activity (ID: {}) for deal {} - Verifying saved values: assignedUserId={}, assignedUser='{}'", 
                    saved.getId(), freshDeal.getId(), saved.getAssignedUserId(), saved.getAssignedUser());
                
                if (saved.getAssignedUserId() == null || saved.getAssignedUser() == null) {
                    log.error("CRITICAL ERROR: Activity was saved with null assignment! Activity ID: {}, Deal ID: {}", 
                        saved.getId(), freshDeal.getId());
                }
            }
            
            // 7. Create "Send Quotes" activity
            if (!hasSendQuotes) {
                Activity sendQuotes = new Activity();
                sendQuotes.setSubject("Send Quotes");
                sendQuotes.setCategory(Activity.ActivityCategory.ACTIVITY);
                sendQuotes.setStatus(Activity.ActivityStatus.OPEN);
                sendQuotes.setDealId(freshDeal.getId());
                sendQuotes.setPersonId(person != null ? person.getId() : null);
                sendQuotes.setOrganizationId(organizationId);
                sendQuotes.setOrganization(organizationName);
                sendQuotes.setAssignedUser(assignedUserName);
                sendQuotes.setAssignedUserId(assignedUserId);
                sendQuotes.setUserId(assignedUserId);
                sendQuotes.setDate(todayStr);
                sendQuotes.setDone(false);
                sendQuotes.setDealName(freshDeal.getName());
                sendQuotes.setPhone(activityPhone);
                
                // Verify values before saving
                if (sendQuotes.getAssignedUserId() == null || sendQuotes.getAssignedUser() == null) {
                    log.error("ERROR: Activity assignment values are null before saving! assignedUserId={}, assignedUser='{}'", 
                        sendQuotes.getAssignedUserId(), sendQuotes.getAssignedUser());
                    return;
                }
                
                log.info("Creating 'Send Quotes' activity for deal {} with assignedUserId={}, assignedUser='{}', organizationId={}", 
                    freshDeal.getId(), sendQuotes.getAssignedUserId(), sendQuotes.getAssignedUser(), sendQuotes.getOrganizationId());
                
                try {
                    Activity saved = activityRepository.save(sendQuotes);
                    log.info("✅ Created 'Send Quotes' activity (ID: {}) for deal {} - assignedUserId={}, assignedUser='{}', organizationId={}", 
                        saved.getId(), freshDeal.getId(), saved.getAssignedUserId(), saved.getAssignedUser(), saved.getOrganizationId());
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("unique_deal_subject")) {
                        log.warn("Activity 'Send Quotes' already exists for deal {} (duplicate prevented by unique constraint)", freshDeal.getId());
                    } else {
                        log.error("Error creating 'Send Quotes' activity for deal {}: {}", freshDeal.getId(), e.getMessage(), e);
                        throw e;
                    }
                }
            }
                
        } catch (Exception e) {
            log.error("Error creating activities for Qualified stage deal {}: {}", deal.getId(), e.getMessage(), e);
            e.printStackTrace();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DealDtos.StageTotalsResponse getStageTotals(Long pipelineId, String status, Long organizationId,
                                                        Long categoryId, Long managerId, String dateFrom,
                                                        String dateTo, String search, String source) {
        log.debug("Stage totals requested with filters: pipelineId={}, status={}, organizationId={}, categoryId={}, managerId={}, dateFrom={}, dateTo={}, search={}, source={}",
            pipelineId, status, organizationId, categoryId, managerId, dateFrom, dateTo, search, source);

        // Validate pipelineId is required
        if (pipelineId == null || pipelineId <= 0) {
            throw new BadRequestException("pipelineId is required and must be a valid positive integer");
        }

        // Validate pipeline exists
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
            .orElseThrow(() -> new ResourceNotFoundException("Pipeline not found with id " + pipelineId));

        // Get all stages for the pipeline (ordered by orderIndex)
        List<Stage> stages = stageRepository.findByPipelineOrderByOrderIndexAsc(pipeline);

        // Build SQL query with filters
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("  d.stage_id, ");
        sql.append("  COUNT(*) as deal_count, ");
        sql.append("  COALESCE(SUM(COALESCE(d.value, 0)), 0) as total_value ");
        sql.append("FROM deals d ");
        sql.append("LEFT JOIN persons p ON d.person_id = p.id ");
        sql.append("LEFT JOIN organizations o ON d.organization_id = o.id ");
        sql.append("LEFT JOIN categories c ON d.category_id = c.id ");
        sql.append("LEFT JOIN pipelines pip ON d.pipeline_id = pip.id ");
        sql.append("WHERE (d.is_deleted IS NULL OR d.is_deleted = false) ");
        sql.append("  AND d.pipeline_id = ? ");

        List<Object> params = new ArrayList<>();
        params.add(pipelineId);

        // Add status filter
        if (status != null && !status.trim().isEmpty() && !"all".equalsIgnoreCase(status.trim())) {
            try {
                DealStatus dealStatus = DealStatus.valueOf(status.trim().toUpperCase());
                sql.append("  AND d.status = ? ");
                params.add(dealStatus.name());
            } catch (IllegalArgumentException e) {
                // Invalid status, ignore
            }
        }

        // Add organization filter
        if (organizationId != null) {
            sql.append("  AND d.organization_id = ? ");
            params.add(organizationId);
        }

        // Add category filter (check both deal.category_id and pipeline.category)
        if (categoryId != null) {
            sql.append("  AND (d.category_id = ? OR pip.category = (SELECT name FROM categories WHERE id = ?)) ");
            params.add(categoryId);
            params.add(categoryId);
        }

        // Add manager filter (deal's owner_id)
        if (managerId != null) {
            sql.append("  AND d.owner_id = ? ");
            params.add(managerId);
        }

        // Add date range filter
        if (dateFrom != null && !dateFrom.trim().isEmpty()) {
            try {
                LocalDate fromDate = LocalDate.parse(dateFrom.trim());
                sql.append("  AND d.created_at >= ? ");
                params.add(fromDate.atStartOfDay());
            } catch (Exception e) {
                log.warn("Invalid dateFrom format: {}. Expected YYYY-MM-DD", dateFrom);
            }
        }
        if (dateTo != null && !dateTo.trim().isEmpty()) {
            try {
                LocalDate toDate = LocalDate.parse(dateTo.trim());
                sql.append("  AND d.created_at <= ? ");
                params.add(toDate.atTime(LocalTime.MAX));
            } catch (Exception e) {
                log.warn("Invalid dateTo format: {}. Expected YYYY-MM-DD", dateTo);
            }
        }

        // Add search filter
        if (search != null && !search.trim().isEmpty()) {
            String searchPattern = "%" + search.trim().toLowerCase() + "%";
            sql.append("  AND (LOWER(d.name) LIKE ? ");
            sql.append("    OR LOWER(d.venue) LIKE ? ");
            sql.append("    OR LOWER(p.name) LIKE ? ");
            sql.append("    OR LOWER(o.name) LIKE ?) ");
            params.add(searchPattern);
            params.add(searchPattern);
            params.add(searchPattern);
            params.add(searchPattern);
        }

        // Add source filter
        if (source != null && !source.trim().isEmpty()) {
            DealSource dealSource = DealSource.fromString(source);
            if (dealSource != null) {
                sql.append("  AND d.deal_source = ? ");
                params.add(dealSource.name());
            }
        }

        sql.append("GROUP BY d.stage_id ");

        // Execute query
        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql.toString(), params.toArray());

        // Create a map of stageId -> totals
        Map<Long, Map<String, Object>> totalsByStageId = new HashMap<>();
        for (Map<String, Object> row : results) {
            Object stageIdObj = row.get("stage_id");
            Long stageId = null;
            if (stageIdObj != null) {
                stageId = ((Number) stageIdObj).longValue();
            }
            // stageId is null for unassigned deals
            totalsByStageId.put(stageId, row);
        }

        // Build response with all stages (even if count is 0)
        List<DealDtos.StageTotal> stageTotals = new ArrayList<>();
        for (Stage stage : stages) {
            Map<String, Object> totals = totalsByStageId.get(stage.getId());
            Long dealCount = totals != null ? ((Number) totals.get("deal_count")).longValue() : 0L;
            BigDecimal totalValue = totals != null ? 
                (BigDecimal) totals.get("total_value") : BigDecimal.ZERO;
            
            stageTotals.add(new DealDtos.StageTotal(
                stage.getId(),
                stage.getName(),
                dealCount,
                totalValue
            ));
        }

        // Get unassigned totals (stage_id is NULL)
        Map<String, Object> unassignedTotals = totalsByStageId.get(null);
        DealDtos.UnassignedTotal unassigned = new DealDtos.UnassignedTotal(
            unassignedTotals != null ? ((Number) unassignedTotals.get("deal_count")).longValue() : 0L,
            unassignedTotals != null ? (BigDecimal) unassignedTotals.get("total_value") : BigDecimal.ZERO
        );

        log.debug("Stage totals: Found {} stages with totals, unassigned: {} deals, {} value",
            stageTotals.size(), unassigned.dealCount, unassigned.totalValue);

        return new DealDtos.StageTotalsResponse(stageTotals, unassigned);
    }
}




