package com.brideside.crm.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import java.time.Instant;
import java.util.Arrays;

@Entity
@Table(name = "organizations")
@NoArgsConstructor
@AllArgsConstructor
public class Organization {

    public enum OrganizationCategory {
        PHOTOGRAPHY("Photography"),
        MAKEUP("Makeup"),
        PLANNING("Planning"),
        DECOR("Decor"),
        BTS("BTS"),
        PLANNING_AND_DECOR("Planning and Decor");

        private final String dbValue;

        OrganizationCategory(String dbValue) {
            this.dbValue = dbValue;
        }

        public String getDbValue() {
            return dbValue;
        }

        public static OrganizationCategory fromDbValue(String value) {
            if (value == null) return null;
            String normalized = value.trim();
            if (normalized.isEmpty()) {
                return null;
            }
            return Arrays.stream(values())
                    .filter(c -> c.dbValue.equalsIgnoreCase(normalized)
                            || c.name().equalsIgnoreCase(normalized.replace(" ", "_")))
                    .findFirst()
                    .orElse(PHOTOGRAPHY);
        }

        @Converter
        public static class CategoryConverter implements AttributeConverter<OrganizationCategory, String> {
            @Override
            public String convertToDatabaseColumn(OrganizationCategory attribute) {
                return attribute == null ? null : attribute.getDbValue();
            }

            @Override
            public OrganizationCategory convertToEntityAttribute(String dbData) {
                return OrganizationCategory.fromDbValue(dbData);
            }
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    @NotFound(action = NotFoundAction.IGNORE)
    private User owner;

    @Column(name = "owner_id", insertable = false, updatable = false)
    private Long ownerId;

    @Column(name = "category", length = 50, nullable = false)
    @Convert(converter = OrganizationCategory.CategoryConverter.class)
    private OrganizationCategory category;

    @Column(length = 500)
    private String address;

    @Column(length = 255)
    private String email;

    @Column(name = "google_calendar_id", length = 255)
    private String googleCalendarId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.FALSE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public OrganizationCategory getCategory() { return category; }
    public void setCategory(OrganizationCategory category) { this.category = category; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getGoogleCalendarId() { return googleCalendarId; }
    public void setGoogleCalendarId(String googleCalendarId) { this.googleCalendarId = googleCalendarId; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

