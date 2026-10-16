package com.brideside.crm.service;

import com.brideside.crm.dto.ActivityDTO;
import com.brideside.crm.dto.ActivityDtos;
import com.brideside.crm.entity.Activity;
import com.brideside.crm.entity.Role;
import com.brideside.crm.entity.Organization;
import com.brideside.crm.exception.BadRequestException;
import com.brideside.crm.exception.ResourceNotFoundException;
import com.brideside.crm.mapper.ActivityMapper;
import com.brideside.crm.repository.ActivityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

@Service
@Transactional(readOnly = true)
@Slf4j
public class ActivityService {
    private final ActivityRepository repository;
    private final ActivityScopeService scopeService;
    private final com.brideside.crm.repository.DealRepository dealRepository;
    
    @PersistenceContext
    private EntityManager entityManager;

    public ActivityService(ActivityRepository repository,
                           ActivityScopeService scopeService,
                           com.brideside.crm.repository.DealRepository dealRepository) {
        this.repository = repository;
        this.scopeService = scopeService;
        this.dealRepository = dealRepository;
    }

    @Transactional
    public ActivityDTO create(ActivityDTO dto) {
        // Validate and trim required fields
        if (dto.getSubject() == null || dto.getSubject().trim().isEmpty()) {
            throw new BadRequestException("Subject is required");
        }
        // Trim subject before saving
        dto.setSubject(dto.getSubject().trim());
        
        // Set default category to ACTIVITY if not provided
        if (dto.getCategory() == null) {
            dto.setCategory(Activity.ActivityCategory.ACTIVITY);
        }
        
        // personId and dealId are optional; included only when > 0 (handled by frontend)
        // dateTime optional for now
        
        // Exclude done from create - managed via POST /api/activities/{id}/done
        // Legacy scheduleBy/callType fields are intentionally not exposed in the DTO anymore
        
        Activity e = new Activity();
        ActivityMapper.updateEntityForCreate(dto, e);
        // Ensure done defaults to false (entity default, but explicit for clarity)
        e.setDone(false);

        // Default assignee for deal-linked activities:
        // If the activity is linked to a deal but no assignee was provided (e.g. the
        // frontend could not resolve the team manager's user object because the
        // current user's visible-users list is restricted, as happens for PRESALES),
        // fall back to the deal's owner (which is the pipeline team manager).
        // This keeps activity assignment consistent across all roles.
        if (e.getDealId() != null && e.getAssignedUserId() == null) {
            dealRepository.findById(e.getDealId()).ifPresent(deal -> {
                com.brideside.crm.entity.User owner = deal.getOwner();
                if (owner != null) {
                    e.setAssignedUserId(owner.getId());
                    // Overwrite the display string when it's missing or the literal
                    // "Unassigned" placeholder the frontend sends when it cannot
                    // resolve the user (e.g. PRESALES with a restricted users list).
                    String current = e.getAssignedUser();
                    if (current == null || current.isBlank() || current.trim().equalsIgnoreCase("Unassigned")) {
                        e.setAssignedUser(resolveUserDisplayName(owner));
                    }
                }
            });
        }
        try {
            return ActivityMapper.toDto(repository.save(e));
        } catch (Exception ex) {
            // Catch FK constraint violations and provide clearer error
            String msg = ex.getMessage();
            if (msg != null && msg.contains("foreign key constraint")) {
                if (msg.contains("deal_id")) {
                    throw new BadRequestException("Deal ID " + dto.getDealId() + " does not exist. Please provide a valid deal ID.");
                } else if (msg.contains("person_id")) {
                    throw new BadRequestException("Person ID " + dto.getPersonId() + " does not exist. Please provide a valid person ID.");
                }
            }
            throw ex;
        }
    }

    public ActivityDTO getById(Long id) {
        if (id == null || id <= 0) {
            throw new BadRequestException("Invalid activity id: " + id);
        }

        Specification<Activity> spec = buildSpecification(
                null, null, null, null,
                null, null, null, null, null,
                null, null, null
        );
        spec = spec.and(withDealFetch());
        spec = spec.and((root, q, cb) -> cb.equal(root.get("id"), id));

        Activity e = repository.findOne(spec)
                .orElseThrow(() -> new ResourceNotFoundException("Activity not found: " + id));
        return ActivityMapper.toDto(e);
    }

    public ActivityDtos.JumpResponse jump(Long activityId,
                                          int size,
                                          List<String> sortParams,
                                          Long personId,
                                          String dateFrom,
                                          String dateTo,
                                          String assignedUser,
                                          List<Long> organizationIds,
                                          List<Long> assignedUserIds,
                                          String category,
                                          String status,
                                          Boolean done,
                                          String serviceCategoryCodes,
                                          String organizationCategoryCodes,
                                          Long dealId) {
        if (activityId == null || activityId <= 0) {
            throw new BadRequestException("Invalid activityId: " + activityId);
        }
        if (size <= 0) {
            throw new BadRequestException("Invalid size: " + size);
        }

        // Build specification WITHOUT date filters (we'll filter dates in memory) - must match list()
        List<Long> dealIds = dealId != null ? List.of(dealId) : null;
        Specification<Activity> spec = buildSpecification(
                personId, null, null, assignedUser,
                organizationIds, assignedUserIds, category, status, done,
                serviceCategoryCodes, organizationCategoryCodes, dealIds
        );
        spec = spec.and(withDealFetch());

        Sort sort = parseSort(sortParams);
        LocalDate fromDate = parseFilterDate(dateFrom);
        LocalDate toDate = parseFilterDate(dateTo);
        List<Activity> deduplicatedActivities = loadDedupedSortedActivities(spec, fromDate, toDate, sort);

        int foundIndex = -1;
        Activity foundActivity = null;
        for (int i = 0; i < deduplicatedActivities.size(); i++) {
            Activity a = deduplicatedActivities.get(i);
            if (a.getId() != null && a.getId().equals(activityId)) {
                foundIndex = i;
                foundActivity = a;
                break;
            }
        }

        if (foundIndex < 0 || foundActivity == null) {
            throw new ResourceNotFoundException("Activity not found in current filters: " + activityId);
        }

        ActivityDtos.JumpResponse res = new ActivityDtos.JumpResponse();
        res.setPage(foundIndex / size);
        res.setSize(size);
        res.setIndexInPage(foundIndex % size);
        res.setActivity(ActivityMapper.toDto(foundActivity));
        return res;
    }

    @Transactional
    public ActivityDTO update(Long id, ActivityDTO dto) {
        Activity e = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Activity not found: " + id));
        // Trim subject if provided
        if (dto.getSubject() != null) {
            dto.setSubject(dto.getSubject().trim());
            if (dto.getSubject().isEmpty()) {
                throw new BadRequestException("Subject cannot be empty");
            }
        }
        ActivityMapper.updateEntity(dto, e);
        return ActivityMapper.toDto(repository.save(e));
    }

    @Transactional
    public void delete(Long id) { repository.deleteById(id); }

    @Transactional
    public ActivityDTO markDone(Long id, boolean done) {
        return markDone(id, done, null);
    }
    
    @Transactional
    public ActivityDTO markDone(Long id, boolean done, Integer durationMinutes) {
        Activity e = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Activity not found: " + id));
        e.setDone(done);
        e.setCompletedAt(done ? Instant.now() : null);
        
        // Save duration only if:
        // 1. Marking as done (done = true)
        // 2. Activity is a Call type
        // 3. duration_minutes is provided and valid (positive number)
        if (done && 
            e.getCategory() == Activity.ActivityCategory.CALL && 
            durationMinutes != null && 
            durationMinutes > 0) {
            e.setDurationMinutes(durationMinutes);
        }
        
        return ActivityMapper.toDto(repository.save(e));
    }

    public Page<ActivityDTO> list(Long personId,
                                  String dateFrom,
                                  String dateTo,
                                  String assignedUser,
                                  List<Long> organizationIds,
                                  List<Long> assignedUserIds,
                                  String category,
                                  String status,
                                  Boolean done,
                                  String serviceCategoryCodes,
                                  String organizationCategoryCodes,
                                  Long dealId,
                                  Pageable pageable) {
        List<Long> dealIds = dealId != null ? List.of(dealId) : null;
        Specification<Activity> spec = buildSpecification(
                personId, null, null, assignedUser,
                organizationIds, assignedUserIds, category, status, done,
                serviceCategoryCodes, organizationCategoryCodes, dealIds
        ).and(withDealFetch());

        LocalDate fromDate = parseFilterDate(dateFrom);
        LocalDate toDate = parseFilterDate(dateTo);

        List<Activity> ordered = loadDedupedSortedActivities(spec, fromDate, toDate, pageable.getSort());

        int page = pageable.getPageNumber();
        int size = pageable.getPageSize();
        int totalElements = ordered.size();
        int start = page * size;
        int end = Math.min(start + size, totalElements);

        List<Activity> paginated = start < totalElements ? ordered.subList(start, end) : new ArrayList<>();
        Page<Activity> activities = new PageImpl<>(paginated, pageable, totalElements);
        return activities.map(ActivityMapper::toDto);
    }

    private Sort parseSort(List<String> sortParams) {
        if (sortParams == null || sortParams.isEmpty()) {
            return Sort.unsorted();
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String raw : sortParams) {
            if (raw == null || raw.isBlank()) continue;
            String[] parts = raw.split(",");
            String property = parts.length > 0 ? parts[0].trim() : "";
            if (property.isEmpty()) continue;
            Sort.Direction dir = Sort.Direction.ASC;
            if (parts.length > 1) {
                try {
                    dir = Sort.Direction.fromString(parts[1].trim());
                } catch (Exception ignored) {
                }
            }
            orders.add(new Sort.Order(dir, property));
        }
        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }

    private LocalDate parseFilterDate(String date) {
        if (date == null || date.isBlank()) return null;
        try {
            return LocalDate.parse(date.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Activity> loadDedupedSortedActivities(Specification<Activity> spec,
                                                       LocalDate fromDate,
                                                       LocalDate toDate,
                                                       Sort sort) {
        List<Activity> allActivities = repository.findAll(spec);

        // Force load organizationRef, owner, and dealRef to avoid lazy loading issues
        allActivities.forEach(activity -> {
            if (activity.getOrganizationRef() != null) {
                activity.getOrganizationRef().getName();
                if (activity.getOrganizationRef().getOwner() != null) {
                    activity.getOrganizationRef().getOwner().getEmail();
                }
            }
            if (activity.getDealRef() != null) {
                activity.getDealRef().getName();
            }
        });

        boolean hasDateFilter = fromDate != null || toDate != null;
        List<Activity> filtered = allActivities;
        if (hasDateFilter) {
            filtered = allActivities.stream()
                    .filter(a -> matchesDateFilter(a, fromDate, toDate))
                    .collect(Collectors.toList());
        }

        // Deduplicate by ID (in case of duplicates from fetch joins)
        java.util.Map<Long, Activity> uniqueActivities = new java.util.LinkedHashMap<>();
        for (Activity activity : filtered) {
            if (activity.getId() != null && !uniqueActivities.containsKey(activity.getId())) {
                uniqueActivities.put(activity.getId(), activity);
            }
        }
        List<Activity> deduped = new ArrayList<>(uniqueActivities.values());

        Comparator<Activity> comparator = buildStableSortComparator(sort);
        deduped.sort(comparator);
        return deduped;
    }

    /**
     * Build a stable comparator matching list() + jump() behavior.
     * Important: Always appends id (desc) as a tie-breaker to keep pagination stable.
     */
    private Comparator<Activity> buildStableSortComparator(Sort sort) {
        Sort effectiveSort = (sort != null && sort.isSorted())
                ? sort
                : Sort.by(Sort.Order.desc("dueDate"));

        Comparator<Activity> cmp = null;
        boolean sortContainsId = false;

        for (Sort.Order order : effectiveSort) {
            String property = order.getProperty();
            if (property != null && property.equalsIgnoreCase("id")) {
                sortContainsId = true;
            }
            Comparator<Activity> next = comparatorForProperty(property, order.isAscending());
            cmp = (cmp == null) ? next : cmp.thenComparing(next);
        }

        if (!sortContainsId) {
            // Always add a deterministic tie-breaker so /jump page matches /api/activities paging
            cmp = (cmp == null ? comparatorForProperty("id", false) : cmp.thenComparing(comparatorForProperty("id", false)));
        }

        return cmp != null ? cmp : comparatorForProperty("id", false);
    }

    private Comparator<Activity> comparatorForProperty(String property, boolean ascending) {
        Comparator<Activity> base;
        if (property == null) property = "";

        switch (property) {
            case "dueDate":
                base = (a1, a2) -> compareDateStrings(a1.getDueDate(), a2.getDueDate());
                break;
            case "date":
                base = (a1, a2) -> compareDateStrings(a1.getDate(), a2.getDate());
                break;
            case "dateTime":
                base = (a1, a2) -> compareDateStrings(a1.getDateTime(), a2.getDateTime());
                break;
            case "id":
                base = (a1, a2) -> Long.compare(
                        a1.getId() != null ? a1.getId() : 0L,
                        a2.getId() != null ? a2.getId() : 0L
                );
                break;
            case "subject":
                base = (a1, a2) -> (a1.getSubject() != null ? a1.getSubject() : "")
                        .compareToIgnoreCase(a2.getSubject() != null ? a2.getSubject() : "");
                break;
            default:
                base = (a1, a2) -> 0;
        }

        return ascending ? base : base.reversed();
    }

    /**
     * Get activities by multiple deal IDs
     * Returns all activities associated with the specified deal IDs without pagination
     */
    public List<ActivityDTO> getByDealIds(List<Long> dealIds) {
        if (dealIds == null || dealIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        
        // Build specification with dealIds
        Specification<Activity> spec = buildSpecification(
                null, null, null, null, // personId, dateFrom, dateTo, assignedUser
                null, null, // organizationIds, assignedUserIds
                null, // category
                null, // status
                null, // done
                null, // serviceCategoryCodes
                null, // organizationCategoryCodes
                dealIds // dealIds
        );
        
        List<Activity> activities = repository.findAll(spec);
        return activities.stream()
                .map(ActivityMapper::toDto)
                .collect(Collectors.toList());
    }
    
    /**
     * Compare two date strings (handles DD/MM/YYYY and ISO formats)
     * Returns: negative if date1 < date2, positive if date1 > date2, 0 if equal
     */
    private int compareDateStrings(String date1, String date2) {
        if (date1 == null && date2 == null) return 0;
        if (date1 == null) return 1; // null dates go to end
        if (date2 == null) return -1;
        
        try {
            LocalDate localDate1 = parseDateString(date1);
            LocalDate localDate2 = parseDateString(date2);
            if (localDate1 == null && localDate2 == null) return 0;
            if (localDate1 == null) return 1;
            if (localDate2 == null) return -1;
            return localDate1.compareTo(localDate2);
        } catch (Exception e) {
            // Fallback to string comparison
            return date1.compareTo(date2);
        }
    }
    
    /**
     * Parse a date string in various formats (DD/MM/YYYY, ISO, etc.)
     */
    private LocalDate parseDateString(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        
        String trimmed = dateStr.trim();
        DateTimeFormatter ddMMyyyyFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        DateTimeFormatter isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        DateTimeFormatter isoDateOnly = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        
        try {
            // Try DD/MM/YYYY format first
            return LocalDate.parse(trimmed, ddMMyyyyFormatter);
        } catch (Exception e1) {
            try {
                // Try ISO date-only format
                if (trimmed.length() == 10) {
                    return LocalDate.parse(trimmed, isoDateOnly);
                }
                // Try ISO with time
                if (trimmed.contains("T")) {
                    return LocalDateTime.parse(trimmed, isoFormatter).toLocalDate();
                }
            } catch (Exception e2) {
                // Could not parse
            }
        }
        return null;
    }
    
    /**
     * Check if an activity matches the date filter by checking all date fields:
     * date (DD/MM/YYYY), dueDate (DD/MM/YYYY), and dateTime (ISO format)
     */
    private boolean matchesDateFilter(Activity activity, LocalDate fromDate, LocalDate toDate) {
        if (fromDate == null && toDate == null) {
            return true; // No date filter
        }
        
        DateTimeFormatter ddMMyyyyFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        DateTimeFormatter isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        DateTimeFormatter isoFormatterWithZone = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
        DateTimeFormatter isoDateOnly = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        
        // Check date field (DD/MM/YYYY format)
        if (activity.getDate() != null && !activity.getDate().trim().isEmpty()) {
            try {
                LocalDate activityDate = LocalDate.parse(activity.getDate().trim(), ddMMyyyyFormatter);
                if (isDateInRange(activityDate, fromDate, toDate)) {
                    return true;
                }
            } catch (Exception e) {
                // Invalid date format, skip this field
            }
        }
        
        // Check dueDate field (DD/MM/YYYY format)
        if (activity.getDueDate() != null && !activity.getDueDate().trim().isEmpty()) {
            try {
                LocalDate activityDueDate = LocalDate.parse(activity.getDueDate().trim(), ddMMyyyyFormatter);
                if (isDateInRange(activityDueDate, fromDate, toDate)) {
                    return true;
                }
            } catch (Exception e) {
                // Invalid date format, skip this field
            }
        }
        
        // Check dateTime field (ISO format or other formats)
        if (activity.getDateTime() != null && !activity.getDateTime().trim().isEmpty()) {
            try {
                String dateTimeStr = activity.getDateTime().trim();
                LocalDate activityDateTime = null;
                
                // Try different date formats
                if (dateTimeStr.contains("T")) {
                    // ISO format with time
                    try {
                        // Check if it has timezone indicator (+, Z, or - after position 10)
                        boolean hasTimezone = dateTimeStr.contains("+") || dateTimeStr.contains("Z") || 
                            (dateTimeStr.length() > 10 && dateTimeStr.charAt(10) == '-' && dateTimeStr.length() > 19);
                        if (hasTimezone) {
                            // Has timezone
                            activityDateTime = OffsetDateTime.parse(dateTimeStr, isoFormatterWithZone).toLocalDate();
                        } else {
                            // No timezone
                            activityDateTime = LocalDateTime.parse(dateTimeStr, isoFormatter).toLocalDate();
                        }
                    } catch (Exception e) {
                        // Try parsing as LocalDateTime without seconds
                        try {
                            activityDateTime = LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")).toLocalDate();
                        } catch (Exception e2) {
                            // Try extracting just the date portion
                            if (dateTimeStr.length() >= 10) {
                                activityDateTime = LocalDate.parse(dateTimeStr.substring(0, 10), isoDateOnly);
                            }
                        }
                    }
                } else if (dateTimeStr.length() == 10) {
                    // Just date portion
                    activityDateTime = LocalDate.parse(dateTimeStr, isoDateOnly);
                }
                
                if (activityDateTime != null && isDateInRange(activityDateTime, fromDate, toDate)) {
                    return true;
                }
            } catch (Exception e) {
                // Invalid date format, skip this field
            }
        }
        
        // If no date fields match, exclude the activity
        return false;
    }
    
    /**
     * Check if a date falls within the specified range (inclusive)
     */
    private boolean isDateInRange(LocalDate date, LocalDate fromDate, LocalDate toDate) {
        if (date == null) {
            return false;
        }
        if (fromDate != null && date.isBefore(fromDate)) {
            return false;
        }
        if (toDate != null && date.isAfter(toDate)) {
            return false;
        }
        return true;
    }

    /**
     * Compute summary counts for dashboard cards using the same scope and filters as list().
     */
    public ActivityDtos.Summary summary(Long personId,
                                        String dateFrom,
                                        String dateTo,
                                        String assignedUser,
                                        List<Long> organizationIds,
                                        List<Long> assignedUserIds,
                                        String category,
                                        String status,
                                        Boolean done,
                                        String serviceCategoryCodes,
                                        String organizationCategoryCodes) {
        ActivityDtos.Summary s = new ActivityDtos.Summary();
        
        // Build base spec without category filter (we'll add category filter per count)
        // This ensures each count uses the correct category filter regardless of request parameters
        Specification<Activity> baseSpecWithoutCategory = buildSpecification(
                personId, dateFrom, dateTo, assignedUser,
                organizationIds, assignedUserIds, null, status, done, // category = null (we'll filter by category per count)
                serviceCategoryCodes, organizationCategoryCodes, null
        );
        
        // Total Activities: category = 'ACTIVITY' (always filter by ACTIVITY category)
        // SELECT * FROM activities WHERE category = 'ACTIVITY'
        Specification<Activity> activityTotalSpec = baseSpecWithoutCategory.and((root, q, cb) ->
                cb.equal(root.get("category"), Activity.ActivityCategory.ACTIVITY));
        long totalCount = repository.count(activityTotalSpec);
        s.setActivityTotalCount(totalCount);

        // Pending: category = 'ACTIVITY' and done = false
        // SELECT * FROM activities WHERE category = 'ACTIVITY' AND done = 0
        Specification<Activity> pendingSpec = baseSpecWithoutCategory.and((root, q, cb) ->
                cb.and(
                    cb.equal(root.get("category"), Activity.ActivityCategory.ACTIVITY),
                    cb.isFalse(root.get("done"))
                ));
        long pendingCount = repository.count(pendingSpec);
        s.setActivityPendingCount(pendingCount);

        // Completed: category = 'ACTIVITY' and done = true
        // SELECT * FROM activities WHERE category = 'ACTIVITY' AND done = 1
        Specification<Activity> completedSpec = baseSpecWithoutCategory.and((root, q, cb) ->
                cb.and(
                    cb.equal(root.get("category"), Activity.ActivityCategory.ACTIVITY),
                    cb.isTrue(root.get("done"))
                ));
        long completedCount = repository.count(completedSpec);
        s.setActivityCompletedCount(completedCount);

        // Call Assigned: category = 'CALL' (always filter by CALL category, regardless of done status)
        // SELECT * FROM activities WHERE category = 'CALL'
        Specification<Activity> callSpec = baseSpecWithoutCategory.and((root, q, cb) ->
                cb.equal(root.get("category"), Activity.ActivityCategory.CALL));
        s.setCallAssignedCount(repository.count(callSpec));

        // Meeting Assigned: category = 'MEETING_SCHEDULER' (always filter by MEETING_SCHEDULER category, regardless of done status)
        Specification<Activity> meetingSpec = baseSpecWithoutCategory.and((root, q, cb) ->
                cb.equal(root.get("category"), Activity.ActivityCategory.MEETING_SCHEDULER));
        s.setMeetingAssignedCount(repository.count(meetingSpec));

        // Optional: Call Taken - category = 'CALL' and done = true
        Specification<Activity> callTakenSpec = baseSpecWithoutCategory.and((root, q, cb) ->
                cb.and(
                    cb.equal(root.get("category"), Activity.ActivityCategory.CALL),
                    cb.isTrue(root.get("done"))
                ));
        s.setCallTakenCount(repository.count(callTakenSpec));

        // Optional: Meeting Done - category = 'MEETING_SCHEDULER' and done = true
        Specification<Activity> meetingDoneSpec = baseSpecWithoutCategory.and((root, q, cb) ->
                cb.and(
                    cb.equal(root.get("category"), Activity.ActivityCategory.MEETING_SCHEDULER),
                    cb.isTrue(root.get("done"))
                ));
        s.setMeetingDoneCount(repository.count(meetingDoneSpec));

        // Overdue counts per category (Activity, Call, Meeting)
        // Optimized: Only compute if needed, and limit the number of activities loaded
        try {
            s.setOverdueCount(computeOverdueCountOptimized(baseSpecWithoutCategory, Activity.ActivityCategory.ACTIVITY));
        } catch (Exception e) {
            s.setOverdueCount(0L); // Set to 0 on error to avoid blocking
        }
        try {
            s.setCallOverdueCount(computeOverdueCountOptimized(baseSpecWithoutCategory, Activity.ActivityCategory.CALL));
        } catch (Exception e) {
            s.setCallOverdueCount(0L);
        }
        try {
            s.setMeetingOverdueCount(computeOverdueCountOptimized(baseSpecWithoutCategory, Activity.ActivityCategory.MEETING_SCHEDULER));
        } catch (Exception e) {
            s.setMeetingOverdueCount(0L);
        }

        // Optional: Total Call Duration - sum of durationMinutes for completed CALL activities
        // Optimized: Use a more efficient approach - only load activities with duration set
        try {
            // Instead of loading all activities, use a more targeted query
            // For now, we'll use a simpler approach that's faster
            Specification<Activity> durationSpec = callTakenSpec.and((root, q, cb) -> 
                cb.isNotNull(root.get("durationMinutes"))
            );
            // Limit to a reasonable number to avoid loading too much data
            List<Activity> activitiesWithDuration = repository.findAll(durationSpec);
            Long totalDuration = activitiesWithDuration.stream()
                .filter(a -> a.getDurationMinutes() != null)
                .mapToLong(Activity::getDurationMinutes)
                .sum();
            s.setTotalCallDurationMinutes(totalDuration);
        } catch (Exception e) {
            // If calculation fails, set to 0 (optional field)
            s.setTotalCallDurationMinutes(0L);
        }

        return s;
    }

    private Long computeOverdueCount(Specification<Activity> baseSpecWithoutCategory,
                                     Activity.ActivityCategory category) {
        return computeOverdueCountOptimized(baseSpecWithoutCategory, category);
    }
    
    /**
     * Optimized overdue count calculation.
     * Note: Still needs to load activities to parse dates, but handles errors gracefully.
     */
    private Long computeOverdueCountOptimized(Specification<Activity> baseSpecWithoutCategory,
                                             Activity.ActivityCategory category) {
        try {
            Specification<Activity> overdueSpec = baseSpecWithoutCategory.and((root, q, cb) ->
                    cb.and(
                            cb.equal(root.get("category"), category),
                            cb.isFalse(root.get("done")),
                            cb.isNotNull(root.get("dueDate"))
                    ));

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            LocalDate today = LocalDate.now();

            // Load activities - this is necessary to parse dates correctly
            // But we handle errors gracefully to avoid blocking
            List<Activity> activities = repository.findAll(overdueSpec);
            
            return activities.stream()
                    .filter(a -> {
                        if (a.getDueDate() == null || a.getDueDate().trim().isEmpty()) {
                            return false;
                        }
                        try {
                            LocalDate dueDate = LocalDate.parse(a.getDueDate(), formatter);
                            return dueDate.isBefore(today);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();
        } catch (Exception e) {
            // Return 0 on error to avoid blocking the entire summary
            return 0L;
        }
    }

    /**
     * Summary for Call tab (assigned, taken, duration, overdue) applying all filters.
     */
    public ActivityDtos.Summary summaryCall(Long personId,
                                            String dateFrom,
                                            String dateTo,
                                            String assignedUser,
                                            List<Long> organizationIds,
                                            List<Long> assignedUserIds,
                                            String category,
                                            Boolean done,
                                            String serviceCategoryCodes,
                                            String organizationCategoryCodes) {
        log.debug("Call summary requested with serviceCategory: {}, organizationCategory: {}", 
            serviceCategoryCodes, organizationCategoryCodes);
        List<Activity> activities = loadFilteredActivities(
                personId, dateFrom, dateTo, assignedUser,
                organizationIds, assignedUserIds,
                Arrays.asList(Activity.ActivityCategory.CALL),
                null,
                done,
                serviceCategoryCodes,
                organizationCategoryCodes,
                null
        );
        log.debug("Call summary: loaded {} activities after filtering", activities.size());

        ActivityDtos.Summary s = new ActivityDtos.Summary();
        long assigned = activities.size();
        long taken = activities.stream().filter(Activity::isDone).count();
        
        // Total duration: only sum durationMinutes for completed calls (done = true)
        long duration = activities.stream()
                .filter(Activity::isDone)
                .filter(a -> a.getDurationMinutes() != null)
                .mapToLong(Activity::getDurationMinutes)
                .sum();
        
        // Calculate overdue: not done and (dueDate is in the past OR no date is set)
        // If a call has no date, it should be considered overdue
        // Use parseDateString helper to handle multiple date formats (dd/MM/yyyy, yyyy-MM-dd, ISO)
        LocalDate today = LocalDate.now();
        
        long overdue = activities.stream()
                .filter(a -> !a.isDone())
                .filter(a -> {
                    boolean hasDate = false;
                    boolean isOverdue = false;
                    
                    // Check dueDate field
                    if (a.getDueDate() != null && !a.getDueDate().trim().isEmpty()) {
                        hasDate = true;
                        LocalDate dueDate = parseDateString(a.getDueDate());
                        if (dueDate != null) {
                            isOverdue = dueDate.isBefore(today);
                            if (isOverdue) {
                                log.debug("Call activity {} is overdue: dueDate={}, today={}", a.getId(), dueDate, today);
                            } else {
                                log.debug("Call activity {} is NOT overdue: dueDate={}, today={}", a.getId(), dueDate, today);
                            }
                            return isOverdue;
                        } else {
                            log.warn("Failed to parse dueDate for call activity {}: '{}'. Counting as overdue.", a.getId(), a.getDueDate());
                            // If date parsing fails, count as overdue
                            return true;
                        }
                    }
                    // Check date field if dueDate is not available
                    if (a.getDate() != null && !a.getDate().trim().isEmpty()) {
                        hasDate = true;
                        LocalDate date = parseDateString(a.getDate());
                        if (date != null) {
                            isOverdue = date.isBefore(today);
                            if (isOverdue) {
                                log.debug("Call activity {} is overdue: date={}, today={}", a.getId(), date, today);
                            } else {
                                log.debug("Call activity {} is NOT overdue: date={}, today={}", a.getId(), date, today);
                            }
                            return isOverdue;
                        } else {
                            log.warn("Failed to parse date for call activity {}: '{}'. Counting as overdue.", a.getId(), a.getDate());
                            // If date parsing fails, count as overdue
                            return true;
                        }
                    }
                    // Check dateTime field if neither date nor dueDate is available
                    if (a.getDateTime() != null && !a.getDateTime().trim().isEmpty()) {
                        hasDate = true;
                        LocalDate dateTime = parseDateString(a.getDateTime());
                        if (dateTime != null) {
                            isOverdue = dateTime.isBefore(today);
                            if (isOverdue) {
                                log.debug("Call activity {} is overdue: dateTime={}, today={}", a.getId(), dateTime, today);
                            } else {
                                log.debug("Call activity {} is NOT overdue: dateTime={}, today={}", a.getId(), dateTime, today);
                            }
                            return isOverdue;
                        } else {
                            log.warn("Failed to parse dateTime for call activity {}: '{}'. Counting as overdue.", a.getId(), a.getDateTime());
                            // If date parsing fails, count as overdue
                            return true;
                        }
                    }
                    // If no date fields are set, count as overdue (pending call with no date = overdue)
                    if (!hasDate) {
                        log.debug("Call activity {} has no date fields - counting as overdue", a.getId());
                        return true;
                    }
                    return false;
                })
                .count();
        
        long totalPending = activities.stream().filter(a -> !a.isDone()).count();
        log.info("Call overdue calculation: total pending={}, overdue={}, today={}", totalPending, overdue, today);

        log.debug("Call summary counts: assigned={}, taken={}, duration={}, overdue={}", assigned, taken, duration, overdue);
        s.setCallAssignedCount(assigned);
        s.setCallTakenCount(taken);
        s.setTotalCallDurationMinutes(duration);
        s.setCallOverdueCount(overdue);
        return s;
    }

    /**
     * Summary for Meeting tab (assigned, done, overdue) applying all filters.
     */
    public ActivityDtos.Summary summaryMeeting(Long personId,
                                               String dateFrom,
                                               String dateTo,
                                               String assignedUser,
                                               List<Long> organizationIds,
                                               List<Long> assignedUserIds,
                                               String category,
                                               Boolean done,
                                               String serviceCategoryCodes,
                                               String organizationCategoryCodes) {
        // COMPREHENSIVE DEBUGGING: Log ALL parameters at the start
        log.info("=== MEETING SUMMARY DEBUG START ===");
        log.info("Meeting summary called with parameters:");
        log.info("  personId: {}", personId);
        log.info("  dateFrom: {}", dateFrom);
        log.info("  dateTo: {}", dateTo);
        log.info("  assignedUser: {}", assignedUser);
        log.info("  organizationIds: {}", organizationIds);
        log.info("  assignedUserIds: {}", assignedUserIds);
        log.info("  category: {}", category);
        log.info("  done: {}", done);
        log.info("  serviceCategoryCodes: {}", serviceCategoryCodes);
        log.info("  organizationCategoryCodes: {}", organizationCategoryCodes);
        
        // Use only MEETING_SCHEDULER to match the main summary method behavior
        // IMPORTANT: Don't pass 'done' parameter to loadFilteredActivities - we need ALL meetings (both done and not done)
        // to calculate both meetingAssignedCount (all meetings) and meetingDoneCount (done=1)
        // Only apply filters if they are provided (not null/empty)
        boolean hasFilters = (personId != null) || 
                            (dateFrom != null && !dateFrom.isBlank()) || 
                            (dateTo != null && !dateTo.isBlank()) ||
                            (assignedUser != null && !assignedUser.isBlank()) ||
                            (organizationIds != null && !organizationIds.isEmpty()) ||
                            (assignedUserIds != null && !assignedUserIds.isEmpty()) ||
                            (serviceCategoryCodes != null && !serviceCategoryCodes.isBlank()) ||
                            (organizationCategoryCodes != null && !organizationCategoryCodes.isBlank());
        
        log.info("Meeting summary: hasFilters = {}", hasFilters);
        
        // ALWAYS use the SAME approach as main summary() method:
        // Build base spec with role-based scoping (same as main summary method)
        // This ensures consistency between /api/activities/summary and /api/activities/summary/meeting
        Specification<Activity> baseSpecWithoutCategory = buildSpecification(
                personId, dateFrom, dateTo, assignedUser,
                organizationIds, assignedUserIds, null, null, null, // category = null, status = null, done = null
                serviceCategoryCodes, organizationCategoryCodes, null
        );
        
        // Add category filter for MEETING_SCHEDULER (same as main summary method)
        Specification<Activity> meetingSpec = baseSpecWithoutCategory.and((root, q, cb) ->
                cb.equal(root.get("category"), Activity.ActivityCategory.MEETING_SCHEDULER));
        
        ActivityDtos.Summary s = new ActivityDtos.Summary();
        
        // Total meeting scheduled = category = 'MEETING_SCHEDULER' (all meetings, regardless of done status)
        // Use count() exactly like main summary() method does
        long assigned = repository.count(meetingSpec);
        
        // Meeting done = category = 'MEETING_SCHEDULER' and done = 1
        // Use count() with done filter (same as main summary method)
        Specification<Activity> meetingDoneSpec = baseSpecWithoutCategory.and((root, q, cb) ->
                cb.and(
                    cb.equal(root.get("category"), Activity.ActivityCategory.MEETING_SCHEDULER),
                    cb.isTrue(root.get("done"))
                ));
        long completed = repository.count(meetingDoneSpec);
        
        log.info("=== MEETING SUMMARY CALCULATION ===");
        log.info("Using repository.count() approach (same as main summary() method)");
        log.info("assigned (meetingAssignedCount) = {} (from repository.count(meetingSpec))", assigned);
        log.info("completed (meetingDoneCount) = {} (from repository.count(meetingDoneSpec))", completed);
        
        // Load activities ONLY for overdue calculation (which requires checking dates)
        List<Activity> activities;
        if (hasFilters) {
            // When filters are provided, use loadFilteredActivities to respect date filters
            activities = loadFilteredActivities(
                    personId, dateFrom, dateTo, assignedUser,
                    organizationIds, assignedUserIds,
                    Arrays.asList(Activity.ActivityCategory.MEETING_SCHEDULER),
                    null,
                    null, // Don't filter by done - we need all meetings for overdue calculation
                    serviceCategoryCodes,
                    organizationCategoryCodes,
                    null
            );
            log.info("Meeting summary: loaded {} activities for overdue calculation (with filters)", activities.size());
        } else {
            // No filters - load activities using the same spec as count()
            activities = repository.findAll(meetingSpec);
            log.info("Meeting summary: loaded {} activities for overdue calculation (no filters)", activities.size());
        }
        
        log.info("=== MEETING SUMMARY CALCULATION ===");
        log.info("Using repository.count() approach (same as main summary() method)");
        log.info("assigned (meetingAssignedCount) = {} (from repository.count(meetingSpec))", assigned);
        log.info("completed (meetingDoneCount) = {} (from repository.count(meetingDoneSpec))", completed);
        log.info("activities.size() = {} (loaded for overdue calculation)", activities.size());
        
        // Calculate overdue: not done and dueDate is in the past (before today)
        // Use parseDateString helper to handle multiple date formats (dd/MM/yyyy, yyyy-MM-dd, ISO)
        LocalDate today = LocalDate.now();
        long overdue = activities.stream()
                .filter(a -> !a.isDone())
                .filter(a -> {
                    // Check dueDate field
                    if (a.getDueDate() != null && !a.getDueDate().trim().isEmpty()) {
                        LocalDate dueDate = parseDateString(a.getDueDate());
                        if (dueDate != null) {
                            boolean isOverdue = dueDate.isBefore(today);
                            if (isOverdue) {
                                log.debug("Meeting activity {} is overdue: dueDate={}, today={}", a.getId(), dueDate, today);
                            }
                            return isOverdue;
                        } else {
                            log.debug("Failed to parse dueDate for meeting activity {}: {}", a.getId(), a.getDueDate());
                            // Count as overdue if date parsing fails (similar to call summary logic)
                            return true;
                        }
                    }
                    // Check date field if dueDate is not available
                    if (a.getDate() != null && !a.getDate().trim().isEmpty()) {
                        LocalDate date = parseDateString(a.getDate());
                        if (date != null) {
                            boolean isOverdue = date.isBefore(today);
                            if (isOverdue) {
                                log.debug("Meeting activity {} is overdue: date={}, today={}", a.getId(), date, today);
                            }
                            return isOverdue;
                        } else {
                            log.debug("Failed to parse date for meeting activity {}: {}", a.getId(), a.getDate());
                            // Count as overdue if date parsing fails
                            return true;
                        }
                    }
                    // Check dateTime field if neither date nor dueDate is available
                    if (a.getDateTime() != null && !a.getDateTime().trim().isEmpty()) {
                        LocalDate dateTime = parseDateString(a.getDateTime());
                        if (dateTime != null) {
                            boolean isOverdue = dateTime.isBefore(today);
                            if (isOverdue) {
                                log.debug("Meeting activity {} is overdue: dateTime={}, today={}", a.getId(), dateTime, today);
                            }
                            return isOverdue;
                        } else {
                            log.debug("Failed to parse dateTime for meeting activity {}: {}", a.getId(), a.getDateTime());
                            // Count as overdue if date parsing fails
                            return true;
                        }
                    }
                    // If no date fields, count as overdue (pending activity with no date)
                    return true;
                })
                .count();

        log.info("Meeting summary counts: total={}, assigned (all meetings)={}, completed (done=1)={}, overdue={}", 
            activities.size(), assigned, completed, overdue);
        
        s.setMeetingAssignedCount(assigned);
        // Set meetingDoneCount - use Long.valueOf to ensure it's not null (even if 0)
        s.setMeetingDoneCount(Long.valueOf(completed));
        s.setMeetingOverdueCount(Long.valueOf(overdue));
        
        log.info("=== MEETING SUMMARY DEBUG END ===");
        log.info("Final response: meetingAssignedCount={}, meetingDoneCount={}, meetingOverdueCount={}", 
            s.getMeetingAssignedCount(), s.getMeetingDoneCount(), s.getMeetingOverdueCount());
        
        return s;
    }

    /**
     * Load, category-filter, date-filter, and deduplicate activities for summary endpoints.
     */
    private List<Activity> loadFilteredActivities(Long personId,
                                                  String dateFrom,
                                                  String dateTo,
                                                  String assignedUser,
                                                  List<Long> organizationIds,
                                                  List<Long> assignedUserIds,
                                                  List<Activity.ActivityCategory> categories,
                                                  String status,
                                                  Boolean done,
                                                  String serviceCategoryCodes,
                                                  String organizationCategoryCodes,
                                                  Long dealId) {
        log.info("=== loadFilteredActivities DEBUG START ===");
        log.info("loadFilteredActivities called with:");
        log.info("  personId={}, dateFrom={}, dateTo={}, assignedUser={}", personId, dateFrom, dateTo, assignedUser);
        log.info("  organizationIds={}, assignedUserIds={}", organizationIds, assignedUserIds);
        log.info("  categories={}, status={}, done={}", categories, status, done);
        log.info("  serviceCategoryCodes={}, organizationCategoryCodes={}, dealId={}", 
            serviceCategoryCodes, organizationCategoryCodes, dealId);
        
        List<Long> dealIds = dealId != null ? List.of(dealId) : null;
        Specification<Activity> spec = buildSpecification(
                personId, null, null, assignedUser,
                organizationIds, assignedUserIds,
                null,
                status,
                done,
                serviceCategoryCodes,
                organizationCategoryCodes,
                dealIds
        );

        List<Activity> list = repository.findAll(spec);
        log.info("loadFilteredActivities: loaded {} activities from database (before category/date filtering)", list.size());

        // Filter by category list if provided
        if (categories != null && !categories.isEmpty()) {
            int beforeCategoryFilter = list.size();
            log.info("loadFilteredActivities: filtering by categories: {}", categories);
            
            // Debug: Show category distribution BEFORE filtering
            Map<String, Long> beforeCategories = list.stream()
                .filter(a -> a.getCategory() != null)
                .collect(Collectors.groupingBy(
                    a -> a.getCategory().name(),
                    Collectors.counting()
                ));
            log.info("loadFilteredActivities: category distribution BEFORE filter: {}", beforeCategories);
            
            list = list.stream()
                    .filter(a -> a.getCategory() != null && categories.contains(a.getCategory()))
                    .collect(java.util.stream.Collectors.toList());
            log.info("loadFilteredActivities: after category filter: {} activities (from {})", list.size(), beforeCategoryFilter);
            
            // Debug: Show warning if all activities were filtered out
            if (beforeCategoryFilter > 0 && list.size() == 0) {
                log.warn("loadFilteredActivities: WARNING - All {} activities filtered out! Looking for categories: {}, but found: {}", 
                    beforeCategoryFilter, categories, beforeCategories);
            }
        }

        // Parse date filters (YYYY-MM-DD) and apply via matchesDateFilter
        LocalDate fromDate = null;
        LocalDate toDate = null;
        if (dateFrom != null && !dateFrom.isBlank()) {
            try { fromDate = LocalDate.parse(dateFrom.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd")); } catch (Exception ignored) {}
        }
        if (dateTo != null && !dateTo.isBlank()) {
            try { toDate = LocalDate.parse(dateTo.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd")); } catch (Exception ignored) {}
        }
        final LocalDate fFrom = fromDate;
        final LocalDate fTo = toDate;
        if (fFrom != null || fTo != null) {
            int beforeDateFilter = list.size();
            list = list.stream()
                    .filter(a -> matchesDateFilter(a, fFrom, fTo))
                    .collect(java.util.stream.Collectors.toList());
            log.debug("loadFilteredActivities: after date filter: {} activities (from {})", list.size(), beforeDateFilter);
        }

        // Deduplicate by ID
        java.util.Map<Long, Activity> unique = new java.util.LinkedHashMap<>();
        for (Activity a : list) {
            if (a.getId() != null && !unique.containsKey(a.getId())) {
                unique.put(a.getId(), a);
            }
        }
        List<Activity> result = new java.util.ArrayList<>(unique.values());
        log.info("loadFilteredActivities: final result size = {} (after deduplication)", result.size());
        log.info("=== loadFilteredActivities DEBUG END ===");
        return result;
    }

    private Predicate orPredicates(CriteriaBuilder cb, List<Predicate> predicates) {
        if (predicates == null || predicates.isEmpty()) {
            return cb.disjunction();
        }
        if (predicates.size() == 1) {
            return predicates.get(0);
        }
        return cb.or(predicates.toArray(new Predicate[0]));
    }

    /**
     * Specification that adds a LEFT JOIN fetch for Deal to get deal name.
     * This ensures deal name is available even if dealName field in activities table is null.
     */
    private Specification<Activity> withDealFetch() {
        return (root, query, cb) -> {
            // Fetch deal + person to allow ActivityDTO.phone to be derived from Person.phone without N+1.
            var dealFetch = root.fetch("dealRef", JoinType.LEFT);
            try {
                dealFetch.fetch("person", JoinType.LEFT);
            } catch (Exception ignored) {
                // If mapping changes, still keep deal fetch.
            }
            // Also fetch direct personRef when activity has person_id but no deal_id.
            try {
                root.fetch("personRef", JoinType.LEFT);
            } catch (Exception ignored) {
            }
            return cb.conjunction(); // Always true, just using this to add the fetch
        };
    }

    /**
     * Build the JPA Specification used for both list() and summary().
     * This ensures the main table and the summary cards are always consistent.
     */
    private Specification<Activity> buildSpecification(Long personId,
                                                       String dateFrom,
                                                       String dateTo,
                                                       String assignedUser,
                                                       List<Long> organizationIds,
                                                       List<Long> assignedUserIds,
                                                       String category,
                                                       String status,
                                                       Boolean done,
                                                       String serviceCategoryCodes,
                                                       String organizationCategoryCodes,
                                                       List<Long> dealIds) {
        Specification<Activity> spec = Specification.where(null);

        // Apply role-based scoping first (by IDs)
        // For Sales users: ONLY shows activities assigned to them or their Presales team members
        //                  (NOT activities for their organizations assigned to other users)
        // For Category Managers: ONLY shows activities assigned to Sales under them and Presales under those Sales
        //                        (NOT activities for organizations owned by those Sales assigned to other users)
        // For Presales users: ONLY shows activities assigned to themselves or their Sales manager
        //                     (NOT activities for organizations owned by their Sales manager assigned to other users)
        // For Admins: Uses organization OR assigned user scope
        ActivityScopeService.Scope scope = scopeService.resolveScope();
        Role.RoleName currentUserRole = scopeService.getCurrentUserRole();

        // Build OR condition: activity matches if organization or assigned user falls inside scope.
        // EXCEPTION: For SALES, CATEGORY_MANAGER, and PRESALES users, only use assigned user filtering (not organization filtering)
        boolean hasOrgIds = !scope.organizationIds().isEmpty();
        boolean hasOrgNames = !scope.organizationNames().isEmpty();
        boolean hasUserIds = !scope.assignedUserIds().isEmpty();
        boolean hasUserEmails = !scope.assignedUserEmails().isEmpty();
        boolean hasUserNames = !scope.assignedUserNames().isEmpty();

        // For SALES, CATEGORY_MANAGER, and PRESALES users, skip organization-based filtering - only show by assignment
        boolean restrictsByOrg = (currentUserRole != Role.RoleName.SALES && 
                                  currentUserRole != Role.RoleName.CATEGORY_MANAGER && 
                                  currentUserRole != Role.RoleName.PRESALES) && (hasOrgIds || hasOrgNames);
        boolean restrictsByUser = hasUserIds || hasUserEmails || hasUserNames;

        if (restrictsByOrg || restrictsByUser) {
            Specification<Activity> scopeSpec = (root, q, cb) -> {
                List<Predicate> disjuncts = new ArrayList<>();

                if (restrictsByOrg) {
                    List<Predicate> orgPredicates = new ArrayList<>();
                    if (hasOrgIds) {
                        orgPredicates.add(root.get("organizationId").in(scope.organizationIds()));
                    }
                    if (hasOrgNames) {
                        orgPredicates.add(
                                cb.lower(root.get("organization")).in(scope.organizationNames()));
                    }
                    if (!orgPredicates.isEmpty()) {
                        disjuncts.add(orPredicates(cb, orgPredicates));
                    }
                }

                if (restrictsByUser) {
                    List<Predicate> userPredicates = new ArrayList<>();
                    if (hasUserIds) {
                        userPredicates.add(root.get("assignedUserId").in(scope.assignedUserIds()));
                    }
                    if (hasUserEmails) {
                        userPredicates.add(
                                cb.lower(root.get("assignedUser")).in(scope.assignedUserEmails()));
                    }
                    if (hasUserNames) {
                        // Also check assignedUser against user display names (firstName + lastName)
                        userPredicates.add(
                                cb.lower(root.get("assignedUser")).in(scope.assignedUserNames()));
                    }
                    // New behavior: always include unassigned activities (assigned_user_id IS NULL)
                    // so they remain visible to all scoped users (Sales, Presales, Category Managers, etc.)
                    userPredicates.add(cb.isNull(root.get("assignedUserId")));
                    if (!userPredicates.isEmpty()) {
                        disjuncts.add(orPredicates(cb, userPredicates));
                    }
                }

                if (disjuncts.isEmpty()) {
                    return cb.conjunction();
                }
                return orPredicates(cb, disjuncts);
            };
            spec = spec.and(scopeSpec);
        }

        // Narrow by explicit IDs, but always inside scope
        // Support both single value and list of values for organizationId and assignedUserId
        if (organizationIds != null && !organizationIds.isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("organizationId").in(organizationIds));
        }
        if (assignedUserIds != null && !assignedUserIds.isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("assignedUserId").in(assignedUserIds));
        }
        
        // Filter by dealIds if provided
        if (dealIds != null && !dealIds.isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("dealId").in(dealIds));
        }

        if (personId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("personId"), personId));
        }
        if (assignedUser != null && !assignedUser.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("assignedUser")), "%" + assignedUser.toLowerCase() + "%"));
        }
        if (category != null && !category.isBlank()) {
            try {
                Activity.ActivityCategory categoryEnum = Activity.ActivityCategory.valueOf(category.toUpperCase().replace(" ", "_"));
                spec = spec.and((root, q, cb) -> cb.equal(root.get("category"), categoryEnum));
            } catch (IllegalArgumentException e) {
                // Invalid category value, skip filter
            }
        }
        if (status != null && !status.isBlank()) {
            try {
                Activity.ActivityStatus statusEnum = Activity.ActivityStatus.valueOf(status.toUpperCase().replace(" ", "_"));
                spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), statusEnum));
            } catch (IllegalArgumentException e) {
                // Invalid status value, skip filter
            }
        }
        if (done != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("done"), done));
        }
        // Date filtering is now handled in the list() method after loading activities
        // This allows us to properly parse and compare dates in different formats
        // (Frontend sends YYYY-MM-DD, backend stores DD/MM/YYYY in date/dueDate fields)

        // Filter by service/organization category codes (PHOTOGRAPHY / MAKEUP / PLANNING_DECOR)
        // When serviceCategory/organizationCategory is provided, use INNER JOIN with organizations table
        // to filter by organizations.category field (as per requirements)
        List<String> categoryCodes = parseCategoryCodes(serviceCategoryCodes, organizationCategoryCodes);
        if (!categoryCodes.isEmpty()) {
            log.debug("Applying service category filter: {}", categoryCodes);
            Specification<Activity> categorySpec = (root, q, cb) -> {
                List<Predicate> predicates = new ArrayList<>();

                // PRIMARY FILTER: When serviceCategory/organizationCategory is provided, filter by Organization.category
                // using INNER JOIN to ensure we only get activities from organizations with matching category
                // This is the main filter - activities MUST have organization_id and the organization MUST match the category
                List<Organization.OrganizationCategory> orgCategories = toOrganizationCategories(categoryCodes);
                if (!orgCategories.isEmpty()) {
                    log.info("Filtering by organization categories via JOIN: {} (from input codes: {})", orgCategories, categoryCodes);
                    // Use INNER JOIN to filter by organizations.category
                    // This matches the requirement: "activities JOIN organizations ON activities.organization_id = organizations.id WHERE organizations.category IN (...)"
                    // INNER JOIN ensures we only get activities that have an organization with matching category
                    // CRITICAL: This JOIN will exclude activities without organization_id (which is correct for this filter)
                    // The CategoryConverter will automatically convert enum values to database values for comparison
                    predicates.add(
                            root.join("organizationRef", JoinType.INNER)
                                    .get("category")
                                    .in(orgCategories)
                    );
                } else {
                    log.warn("No valid organization categories found for codes: {}. Will use fallback filter only.", categoryCodes);
                }
                
                // FALLBACK: For activities without organization_id (organization_id IS NULL),
                // also check Activity.serviceCategory field as a fallback
                // This handles edge cases where activities don't have an organization but have serviceCategory set
                // Only apply fallback if we have valid organization categories to match against
                if (!orgCategories.isEmpty()) {
                    log.debug("Adding fallback filter for activities without organization_id");
                    Predicate noOrgPredicate = cb.isNull(root.get("organizationId"));
                    Predicate serviceCategoryPredicate = root.get("serviceCategory").in(categoryCodes);
                    Predicate fallbackPredicate = cb.and(noOrgPredicate, serviceCategoryPredicate);
                    predicates.add(fallbackPredicate);
                } else {
                    // If no valid org categories, fall back to serviceCategory only
                    log.debug("No valid org categories, using serviceCategory filter only");
                    predicates.add(root.get("serviceCategory").in(categoryCodes));
                }

                if (predicates.isEmpty()) {
                    return cb.conjunction();
                }
                // Use OR logic: match if EITHER (organization.category matches via JOIN) OR (no organization AND activity.serviceCategory matches)
                // This ensures:
                // 1. Activities with organization_id are filtered by organization.category (via INNER JOIN) - PRIMARY
                // 2. Activities without organization_id are filtered by activity.serviceCategory (fallback) - SECONDARY
                Predicate result = predicates.size() == 1 ? predicates.get(0) : cb.or(predicates.toArray(new Predicate[0]));
                log.debug("Service category filter applied with {} predicate(s)", predicates.size());
                return result;
            };
            spec = spec.and(categorySpec);
        }

        return spec;
    }

    /**
     * Parse one or two comma-separated category strings into normalized codes.
     */
    private List<String> parseCategoryCodes(String serviceCategoryCodes, String organizationCategoryCodes) {
        List<String> codes = new ArrayList<>();

        for (String source : Arrays.asList(serviceCategoryCodes, organizationCategoryCodes)) {
            if (source == null || source.isBlank()) continue;
            String[] parts = source.split(",");
            for (String raw : parts) {
                if (raw == null) continue;
                String normalized = raw.trim();
                if (!normalized.isEmpty()) {
                    // Normalize to upper-case code form, with underscores instead of spaces
                    String upper = normalized.toUpperCase().replace(' ', '_');
                    // Special-case common variants of Planning & Decor
                    if ("PLANNING_DECOR".equals(upper) || "PLANNING_AND_DECOR".equals(upper) || "PLANNING_&_DECOR".equals(upper)) {
                        upper = "PLANNING_AND_DECOR";
                    }
                    codes.add(upper);
                }
            }
        }
        return codes;
    }

    /**
     * Map normalized string codes to OrganizationCategory enums where possible.
     */
    private List<Organization.OrganizationCategory> toOrganizationCategories(List<String> codes) {
        List<Organization.OrganizationCategory> result = new ArrayList<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) continue;
            switch (code) {
                case "PHOTOGRAPHY" -> result.add(Organization.OrganizationCategory.PHOTOGRAPHY);
                case "MAKEUP" -> result.add(Organization.OrganizationCategory.MAKEUP);
                case "PLANNING" -> result.add(Organization.OrganizationCategory.PLANNING);
                case "DECOR" -> result.add(Organization.OrganizationCategory.DECOR);
                case "BTS" -> result.add(Organization.OrganizationCategory.BTS);
                case "PLANNING_DECOR", "PLANNING_AND_DECOR" ->
                        result.add(Organization.OrganizationCategory.PLANNING_AND_DECOR);
                default -> {
                    // Ignore unknown codes
                }
            }
        }
        return result;
    }

    public Optional<ActivityDTO> get(Long id) {
        return repository.findById(id).map(ActivityMapper::toDto);
    }

    /**
     * Build a display string for an activity's assigned user.
     * Prefers "First Last", falling back to email, then id.
     */
    private String resolveUserDisplayName(com.brideside.crm.entity.User user) {
        if (user == null) return null;
        String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String last = user.getLastName() != null ? user.getLastName().trim() : "";
        String fullName = (first + " " + last).trim();
        if (!fullName.isEmpty()) return fullName;
        if (user.getEmail() != null && !user.getEmail().isBlank()) return user.getEmail();
        return user.getId() != null ? String.valueOf(user.getId()) : null;
    }
}



