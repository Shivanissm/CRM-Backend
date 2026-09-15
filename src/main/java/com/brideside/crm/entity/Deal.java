package com.brideside.crm.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "deals")
@NoArgsConstructor
@AllArgsConstructor
public class Deal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal value;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id", nullable = true)
    private Person person;

    // Direct FK field for performance (avoid loading `person` just to get id)
    @Column(name = "person_id", insertable = false, updatable = false)
    private Long personId;

    // Denormalized snapshot for fast search/sort/display (backfilled from persons.name)
    @Column(name = "person_name", length = 255, nullable = true)
    private String personName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = true)
    private Pipeline pipeline;

    // Direct foreign key field to avoid JOINs when filtering by pipeline_id
    @Column(name = "pipeline_id", insertable = false, updatable = false)
    private Long pipelineId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stage_id", nullable = true)
    private Stage stage;

    // Direct foreign key field to avoid JOINs when filtering by stage_id
    @Column(name = "stage_id", insertable = false, updatable = false)
    private Long stageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id")
    private Source source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = true)
    private Organization organization;

    // Direct foreign key field to avoid JOINs when filtering by organization_id
    @Column(name = "organization_id", insertable = false, updatable = false)
    private Long organizationId;

    /**
     * Denormalized owner (sales user): from linked person.owner when present, else organization.owner.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = true)
    @NotFound(action = NotFoundAction.IGNORE)
    private User owner;

    @Column(name = "owner_id", insertable = false, updatable = false)
    private Long ownerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = true)
    private Category dealCategory;

    // Direct foreign key field to avoid JOINs when filtering by category_id
    @Column(name = "category_id", insertable = false, updatable = false)
    private Long categoryId;

    private String eventType; // free text per ER

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DealStatus status = DealStatus.IN_PROGRESS;

    // Legacy column kept for backward DB compatibility until migration removes it
    @Column(name = "won", nullable = false)
    private Boolean legacyWon = false;

    // Compatibility with existing DB where deals has a non-null 'contact_number' column
    @Column(name = "contact_number", nullable = true)
    private String contactNumber;

    // Compatibility with existing DB where deals has a non-null 'user_name' column
    @Column(name = "user_name", nullable = true)
    private String userName;

    @Column(precision = 12, scale = 2)
    private BigDecimal commissionAmount; // computed on write

    // Additional optional fields
    @Column(name = "venue", nullable = true)
    private String venue;

    @Column(name = "phone_number", nullable = true)
    private String phoneNumber;

    @Column(name = "city", nullable = true, length = 255)
    private String city;

    @Column(name = "notes", nullable = true, columnDefinition = "TEXT")
    private String notes;

    @Column(name = "final_thank_you_sent", nullable = true)
    private Boolean finalThankYouSent;

    @Column(name = "contract_shared", nullable = true)
    private Boolean contractShared;

    @Column(name = "whatsapp_group_created", nullable = true)
    private Boolean whatsappGroupCreated;

    @Column(name = "event_date_asked", nullable = true)
    private Boolean eventDateAsked;

    @Column(name = "contact_number_asked", nullable = true)
    private Boolean contactNumberAsked;

    @Column(name = "venue_asked", nullable = true)
    private Boolean venueAsked;

    @Column(name = "event_date", nullable = true)
    private LocalDate eventDate; // Legacy field - kept for backward compatibility

    @Column(name = "event_dates", columnDefinition = "JSON")
    private String eventDates; // JSON array of dates: ["2024-01-01", "2024-01-02"]

    @Column(name = "google_calendar_event_id", length = 255)
    private String googleCalendarEventId; // Legacy field - kept for backward compatibility

    @Column(name = "google_calendar_event_ids", columnDefinition = "JSON")
    private String googleCalendarEventIds; // JSON object mapping dates to event IDs: {"2024-01-01": "event_id_1", "2024-01-02": "event_id_2"}

    @Enumerated(EnumType.STRING)
    @Column(name = "label_enum", length = 50, nullable = true, insertable = false, updatable = false)
    private DealLabel labelEnum; // Legacy enum field - kept for backward compatibility (read-only)

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "deal_labels",
        joinColumns = @JoinColumn(name = "deal_id"),
        inverseJoinColumns = @JoinColumn(name = "label_id")
    )
    @BatchSize(size = 50)
    @Fetch(FetchMode.SUBSELECT)
    private Set<Label> labels = new HashSet<>(); // Multiple labels from labels table

    @Convert(converter = com.brideside.crm.converter.DealSourceConverter.class)
    @Column(name = "deal_source", length = 50, nullable = true)
    private DealSource dealSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "deal_sub_source", length = 50, nullable = true)
    private DealSubSource dealSubSource;

    @Column(name = "is_diverted", nullable = false)
    private Boolean isDiverted = Boolean.FALSE;

    /**
     * Legacy column used by Instagram / DM flows. Not exposed in API, but must be set
     * to satisfy non-null constraint on existing databases.
     */
    @Column(name = "reentered_via_direct_message", nullable = false)
    private Boolean reenteredViaDirectMessage = Boolean.FALSE;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = Boolean.FALSE;

    @Enumerated(EnumType.STRING)
    @Column(name = "lost_reason", length = 50, nullable = true)
    private DealLostReason lostReason;

    @Column(name = "client_budget", precision = 15, scale = 2, nullable = true)
    private BigDecimal clientBudget;

    @Column(name = "won_at", nullable = true)
    private LocalDateTime wonAt;

    @Column(name = "lost_at", nullable = true)
    private LocalDateTime lostAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referenced_deal_id", nullable = true)
    private Deal referencedDeal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referenced_pipeline_id", nullable = true)
    private Pipeline referencedPipeline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_pipeline_id", nullable = true)
    private Pipeline sourcePipeline;

    @Column(name = "pipeline_history", columnDefinition = "JSON")
    private String pipelineHistory; // JSON array of pipeline IDs: [1, 2, 3]

    @Enumerated(EnumType.STRING)
    @Column(name = "created_by", length = 10)
    private CreatedByType createdBy = CreatedByType.USER;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_name", length = 255)
    private String createdByName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", insertable = false, updatable = false)
    private User createdByUser;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = true)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public Person getPerson() { return person; }
    public void setPerson(Person person) { this.person = person; }
    public Long getPersonId() { return personId; }
    public String getPersonName() { return personName; }
    public void setPersonName(String personName) { this.personName = personName; }
    public Pipeline getPipeline() { return pipeline; }
    public void setPipeline(Pipeline pipeline) { this.pipeline = pipeline; }
    public Long getPipelineId() { return pipelineId; }
    public Stage getStage() { return stage; }
    public void setStage(Stage stage) { this.stage = stage; }
    public Long getStageId() { return stageId; }
    public void setStageId(Long stageId) { this.stageId = stageId; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization organization) { this.organization = organization; }
    public Long getOrganizationId() { return organizationId; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public Long getOwnerId() { return ownerId; }
    public Category getDealCategory() { return dealCategory; }
    public void setDealCategory(Category dealCategory) { this.dealCategory = dealCategory; }
    public Long getCategoryId() { return categoryId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public DealStatus getStatus() { return status; }
    public void setStatus(DealStatus status) { this.status = status; }
    public Boolean getLegacyWon() { return legacyWon; }
    public void setLegacyWon(Boolean legacyWon) { this.legacyWon = legacyWon; }
    public String getContactNumber() { return contactNumber; }
    public void setContactNumber(String contactNumber) { this.contactNumber = contactNumber; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public void setCommissionAmount(BigDecimal commissionAmount) { this.commissionAmount = commissionAmount; }
    public String getVenue() { return venue; }
    public void setVenue(String venue) { this.venue = venue; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Boolean getFinalThankYouSent() { return finalThankYouSent; }
    public void setFinalThankYouSent(Boolean finalThankYouSent) { this.finalThankYouSent = finalThankYouSent; }
    public Boolean getContractShared() { return contractShared; }
    public void setContractShared(Boolean contractShared) { this.contractShared = contractShared; }
    public Boolean getWhatsappGroupCreated() { return whatsappGroupCreated; }
    public void setWhatsappGroupCreated(Boolean whatsappGroupCreated) { this.whatsappGroupCreated = whatsappGroupCreated; }
    public Boolean getReenteredViaDirectMessage() { return reenteredViaDirectMessage; }
    public void setReenteredViaDirectMessage(Boolean reenteredViaDirectMessage) { this.reenteredViaDirectMessage = reenteredViaDirectMessage; }
    public Boolean getEventDateAsked() { return eventDateAsked; }
    public void setEventDateAsked(Boolean eventDateAsked) { this.eventDateAsked = eventDateAsked; }
    public Boolean getContactNumberAsked() { return contactNumberAsked; }
    public void setContactNumberAsked(Boolean contactNumberAsked) { this.contactNumberAsked = contactNumberAsked; }
    public Boolean getVenueAsked() { return venueAsked; }
    public void setVenueAsked(Boolean venueAsked) { this.venueAsked = venueAsked; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }
    
    public String getEventDates() { return eventDates; }
    public void setEventDates(String eventDates) { this.eventDates = eventDates; }
    public DealLabel getLabelEnum() { return labelEnum; }
    public void setLabelEnum(DealLabel labelEnum) { this.labelEnum = labelEnum; }
    public Set<Label> getLabels() { return labels; }
    public void setLabels(Set<Label> labels) { this.labels = labels != null ? labels : new HashSet<>(); }
    public DealSource getDealSource() { return dealSource; }
    public void setDealSource(DealSource dealSource) { this.dealSource = dealSource; }
    public DealSubSource getDealSubSource() { return dealSubSource; }
    public void setDealSubSource(DealSubSource dealSubSource) { this.dealSubSource = dealSubSource; }
    public Boolean getIsDiverted() { return isDiverted; }
    public void setIsDiverted(Boolean isDiverted) { this.isDiverted = isDiverted; }
    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean isDeleted) { this.isDeleted = isDeleted; }
    public DealLostReason getLostReason() { return lostReason; }
    public void setLostReason(DealLostReason lostReason) { this.lostReason = lostReason; }
    public BigDecimal getClientBudget() { return clientBudget; }
    public void setClientBudget(BigDecimal clientBudget) { this.clientBudget = clientBudget; }
    public LocalDateTime getWonAt() { return wonAt; }
    public void setWonAt(LocalDateTime wonAt) { this.wonAt = wonAt; }
    public LocalDateTime getLostAt() { return lostAt; }
    public void setLostAt(LocalDateTime lostAt) { this.lostAt = lostAt; }
    public Deal getReferencedDeal() { return referencedDeal; }
    public void setReferencedDeal(Deal referencedDeal) { this.referencedDeal = referencedDeal; }
    public Pipeline getReferencedPipeline() { return referencedPipeline; }
    public void setReferencedPipeline(Pipeline referencedPipeline) { this.referencedPipeline = referencedPipeline; }
    public Pipeline getSourcePipeline() { return sourcePipeline; }
    public void setSourcePipeline(Pipeline sourcePipeline) { this.sourcePipeline = sourcePipeline; }
    public String getPipelineHistory() { return pipelineHistory; }
    public void setPipelineHistory(String pipelineHistory) { this.pipelineHistory = pipelineHistory; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getGoogleCalendarEventId() { return googleCalendarEventId; }
    public void setGoogleCalendarEventId(String googleCalendarEventId) { this.googleCalendarEventId = googleCalendarEventId; }
    
    public String getGoogleCalendarEventIds() { return googleCalendarEventIds; }
    public void setGoogleCalendarEventIds(String googleCalendarEventIds) { this.googleCalendarEventIds = googleCalendarEventIds; }

    public CreatedByType getCreatedBy() { return createdBy; }
    public void setCreatedBy(CreatedByType createdBy) { this.createdBy = createdBy; }

    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    public User getCreatedByUser() { return createdByUser; }
    public void setCreatedByUser(User createdByUser) { this.createdByUser = createdByUser; }

    @PrePersist
    public void prePersist() {
        if (this.updatedAt == null) {
            this.updatedAt = this.createdAt;
        }
        if (this.createdBy == null) {
            this.createdBy = CreatedByType.USER;
        }

        // Ensure denormalized column is populated on inserts.
        // For updates, we rely on DB trigger + keep this logic insert-safe.
        if (this.personName == null) {
            this.personName = this.person != null ? this.person.getName() : null;
        }
        syncDealOwnerFromLinkedEntitiesIfMissing();
    }

    private void syncDealOwnerFromLinkedEntitiesIfMissing() {
        if (this.owner != null) {
            return;
        }
        User resolved = null;
        if (person != null && person.getOwner() != null) {
            resolved = person.getOwner();
        } else if (organization != null && organization.getOwner() != null) {
            resolved = organization.getOwner();
        }
        this.owner = resolved;
    }
}


