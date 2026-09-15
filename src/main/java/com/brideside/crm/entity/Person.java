package com.brideside.crm.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "persons")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "instagram_id", length = 255)
    private String instagramId;

    @Column(length = 50)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(name = "lead_date")
    private LocalDate leadDate;

    @Column(name = "venue")
    private String venue;

    @Column(name = "city", length = 255)
    private String city;

    // Maps to legacy wedding_date column in DB, represents event date for the person
    // Note: wedding_date is VARCHAR(255) in DB, so using String type
    @Column(name = "wedding_date", length = 255)
    private String eventDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    @NotFound(action = NotFoundAction.IGNORE)
    private User owner;

    @Column(name = "owner_id", insertable = false, updatable = false)
    private Long ownerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "label_enum", length = 50, nullable = true, insertable = false, updatable = false)
    private PersonLabel labelEnum; // Legacy enum field - kept for backward compatibility (read-only)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "label_id", nullable = true)
    private Label label; // Custom label from labels table

    @Convert(converter = com.brideside.crm.converter.DealSourceConverter.class)
    @Column(name = "source", length = 50)
    private DealSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "sub_source", length = 50)
    private DealSubSource subSource;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = Boolean.FALSE;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.leadDate == null) {
            this.leadDate = LocalDate.now();
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getInstagramId() {
        return instagramId;
    }

    public void setInstagramId(String instagramId) {
        this.instagramId = instagramId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getLeadDate() {
        return leadDate;
    }

    public void setLeadDate(LocalDate leadDate) {
        this.leadDate = leadDate;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getEventDate() {
        return eventDate;
    }

    public void setEventDate(String eventDate) {
        this.eventDate = eventDate;
    }

    public Organization getOrganization() {
        return organization;
    }

    public void setOrganization(Organization organization) {
        this.organization = organization;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public PersonLabel getLabelEnum() {
        return labelEnum;
    }

    public void setLabelEnum(PersonLabel labelEnum) {
        this.labelEnum = labelEnum;
    }

    public Label getLabel() {
        return label;
    }

    public void setLabel(Label label) {
        this.label = label;
    }

    public DealSource getSource() {
        return source;
    }

    public void setSource(DealSource source) {
        this.source = source;
    }

    public DealSubSource getSubSource() {
        return subSource;
    }

    public void setSubSource(DealSubSource subSource) {
        this.subSource = subSource;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Boolean isDeleted) {
        this.isDeleted = isDeleted;
    }

    public enum PersonLabel {
        BRIDAL_MAKEUP("Bridal makeup"),
        PARTY_MAKEUP("Party makeup"),
        ENGAGEMENT("Engagement"),
        RECEPTION("Reception"),
        OTHER("Other");

        private final String displayName;

        PersonLabel(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

}
