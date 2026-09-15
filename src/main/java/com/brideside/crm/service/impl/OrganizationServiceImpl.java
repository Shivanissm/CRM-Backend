package com.brideside.crm.service.impl;

import com.brideside.crm.dto.OrganizationDtos;
import com.brideside.crm.entity.Organization;
import com.brideside.crm.entity.Pipeline;
import com.brideside.crm.entity.Role;
import com.brideside.crm.entity.User;
import com.brideside.crm.entity.BridesideVendor;
import com.brideside.crm.exception.BadRequestException;
import com.brideside.crm.exception.ResourceNotFoundException;
import com.brideside.crm.repository.BridesideVendorRepository;
import com.brideside.crm.repository.OrganizationRepository;
import com.brideside.crm.repository.PersonRepository;
import com.brideside.crm.repository.DealRepository;
import com.brideside.crm.repository.ActivityRepository;
import com.brideside.crm.repository.OrganizationOnboardingProgressRepository;
import com.brideside.crm.repository.OrganizationActivationRepository;
import com.brideside.crm.repository.PipelineRepository;
import com.brideside.crm.repository.UserRepository;
import com.brideside.crm.repository.EventPricingRepository;
import com.brideside.crm.repository.VendorTeamSizeRepository;
import com.brideside.crm.repository.VendorTeamMemberRepository;
import com.brideside.crm.repository.VendorAssetRepository;
import com.brideside.crm.repository.VendorDataRepository;
import com.brideside.crm.service.BridesideVendorService;
import com.brideside.crm.service.OrganizationProgressService;
import com.brideside.crm.service.OrganizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class OrganizationServiceImpl implements OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final PersonRepository personRepository;
    private final DealRepository dealRepository;
    private final ActivityRepository activityRepository;
    private final PipelineRepository pipelineRepository;
    private final BridesideVendorService bridesideVendorService;
    private final VendorAssetRepository vendorAssetRepository;
    private final OrganizationOnboardingProgressRepository progressRepository;
    private final OrganizationActivationRepository activationRepository;
    private final BridesideVendorRepository bridesideVendorRepository;
    private final EventPricingRepository eventPricingRepository;
    private final VendorTeamSizeRepository vendorTeamSizeRepository;
    private final VendorTeamMemberRepository vendorTeamMemberRepository;
    private final VendorDataRepository vendorDataRepository;
    private final OrganizationProgressService organizationProgressService;
    private static final Set<Role.RoleName> ALLOWED_OWNER_ROLES =
            EnumSet.of(Role.RoleName.SALES, Role.RoleName.CATEGORY_MANAGER);

    public OrganizationServiceImpl(OrganizationRepository organizationRepository,
                                   UserRepository userRepository,
                                   PersonRepository personRepository,
                                   DealRepository dealRepository,
                                   ActivityRepository activityRepository,
                                   PipelineRepository pipelineRepository,
                                   BridesideVendorService bridesideVendorService,
                                   BridesideVendorRepository bridesideVendorRepository,
                                   VendorAssetRepository vendorAssetRepository,
                                   EventPricingRepository eventPricingRepository,
                                   VendorTeamSizeRepository vendorTeamSizeRepository,
                                   VendorTeamMemberRepository vendorTeamMemberRepository,
                                   VendorDataRepository vendorDataRepository,
                                   OrganizationOnboardingProgressRepository progressRepository,
                                   OrganizationActivationRepository activationRepository,
                                   OrganizationProgressService organizationProgressService) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.personRepository = personRepository;
        this.dealRepository = dealRepository;
        this.activityRepository = activityRepository;
        this.pipelineRepository = pipelineRepository;
        this.bridesideVendorService = bridesideVendorService;
        this.bridesideVendorRepository = bridesideVendorRepository;
        this.vendorAssetRepository = vendorAssetRepository;
        this.eventPricingRepository = eventPricingRepository;
        this.vendorTeamSizeRepository = vendorTeamSizeRepository;
        this.vendorTeamMemberRepository = vendorTeamMemberRepository;
        this.vendorDataRepository = vendorDataRepository;
        this.progressRepository = progressRepository;
        this.activationRepository = activationRepository;
        this.organizationProgressService = organizationProgressService;
    }

    @Override
    public OrganizationDtos.OrganizationResponse create(OrganizationDtos.OrganizationRequest request) {
        Organization organization = new Organization();
        organization.setName(trimmed(request.getName()));
        organization.setOwner(resolveOwner(request.getOwnerId()));
        organization.setCategory(resolveCategory(request.getCategory()));
        organization.setAddress(trimmed(request.getAddress()));
        organization.setEmail(trimmed(request.getEmail()));
        Organization saved = organizationRepository.save(organization);
        // Ensure org always has a linked brideside_vendors entry and vendor_assets entry
        bridesideVendorService.createVendorForOrganization(saved.getId(), null);
        organizationProgressService.recomputeAndPersistProgress(saved.getId());
        return toResponse(organizationRepository.findById(saved.getId()).orElse(saved));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationDtos.OrganizationResponse> list() {
        return organizationRepository.findAll()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationDtos.OrganizationResponse get(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Override
    public OrganizationDtos.OrganizationResponse update(Long id, OrganizationDtos.OrganizationRequest request) {
        Organization organization = getOrThrow(id);
        organization.setName(trimmed(request.getName()));
        organization.setOwner(resolveOwner(request.getOwnerId()));
        organization.setCategory(resolveCategory(request.getCategory()));
        organization.setAddress(trimmed(request.getAddress()));
        organization.setEmail(trimmed(request.getEmail()));
        organization.setGoogleCalendarId(trimmed(request.getGoogleCalendarId()));
        Organization saved = organizationRepository.save(organization);
        bridesideVendorService.syncAccountOwnerFromOrganization(saved.getId());
        organizationProgressService.recomputeAndPersistProgress(saved.getId());
        return toResponse(organizationRepository.findById(saved.getId()).orElse(saved));
    }

    @Override
    public void delete(Long id) {
        Organization organization = getOrThrow(id);
        // Detach persons linked to this organization (set organization_id = null, keep persons)
        personRepository.clearOrganizationByOrganizationId(id);

        // Soft-delete deals linked to this organization (set isDeleted = true and clear organization)
        dealRepository.softDeleteDealsByOrganizationId(id);

        // Hard-delete activities linked to this organization
        activityRepository.deleteActivitiesByOrganizationId(id);

        // Soft-delete pipelines linked to this organization and detach them
        List<Pipeline> pipelines = pipelineRepository.findByOrganization(organization);
        if (!pipelines.isEmpty()) {
            for (Pipeline pipeline : pipelines) {
                pipeline.setDeleted(Boolean.TRUE);
                pipeline.setOrganization(null);
            }
            pipelineRepository.saveAll(pipelines);
        }

        // Delete vendor-dependent tables that may block vendor deletion due to foreign key constraints.
        // Example observed issue: events_pricing.vendor_id -> brideside_vendors.id
        List<BridesideVendor> vendors = bridesideVendorRepository.findByOrganization_Id(id);
        for (BridesideVendor vendor : vendors) {
            // These repositories generate delete queries by vendor_id.
            eventPricingRepository.deleteByVendor_Id(vendor.getId());
            vendorTeamSizeRepository.deleteByVendor_Id(vendor.getId());
            vendorTeamMemberRepository.deleteByVendor_Id(vendor.getId());
            vendorDataRepository.deleteByVendor_Id(vendor.getId());
        }

        // Delete vendor_assets linked to this organization (before org delete due to FK)
        vendorAssetRepository.deleteByOrganization_Id(id);

        // Delete organization_onboarding_progress (before org delete due to FK)
        progressRepository.deleteByOrganization_Id(id);

        // Delete organization_activation (before org delete due to FK)
        activationRepository.findByOrganization_Id(id)
                .ifPresent(activationRepository::delete);

        // Now it's safe to delete the organization
        organizationRepository.delete(organization);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationDtos.OwnerOption> listOwnerOptions() {
        return userRepository.findByRole_NameInAndActiveTrue(ALLOWED_OWNER_ROLES)
                .stream()
                .map(this::toOwnerOption)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationDtos.CategoryOption> listCategoryOptions() {
        return OrganizationDtos.allCategoryOptions();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationDtos.OrganizationResponse> listAccessibleForCurrentUser(String currentUserEmail) {
        User currentUser = getCurrentUser(currentUserEmail);
        Role.RoleName roleName = currentUser.getRole() != null ? currentUser.getRole().getName() : null;

        if (roleName == null) {
            return List.of();
        }

        List<Organization> organizations;

        switch (roleName) {
            case ADMIN:
                // Admin can see all organizations
                organizations = organizationRepository.findAll();
                break;

            case CATEGORY_MANAGER:
                // Category Manager can see:
                // 1. Organizations owned by them
                // 2. Organizations owned by Sales/Presales under them
                List<Long> accessibleOwnerIds = findAccessibleOwnerIdsForCategoryManager(currentUser);
                organizations = organizationRepository.findByOwner_IdIn(accessibleOwnerIds);
                break;

            case SALES:
                // Sales can see:
                // 1. Organizations owned by them
                // 2. Organizations owned by Presales under them
                List<Long> salesAndPresalesOwnerIds = findAccessibleOwnerIdsForSales(currentUser);
                organizations = organizationRepository.findByOwner_IdIn(salesAndPresalesOwnerIds);
                break;

            case PRESALES:
                // Presales can see organizations owned by them
                organizations = organizationRepository.findByOwner_Id(currentUser.getId());
                break;

            default:
                organizations = List.of();
        }

        return organizations.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Find all user IDs that a Category Manager can access (for organization ownership)
     * Includes: the category manager themselves, direct Sales/Presales reports, and Sales who have Presales under them
     */
    private List<Long> findAccessibleOwnerIdsForCategoryManager(User categoryManager) {
        List<Long> ownerIds = new java.util.ArrayList<>();
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
        List<Long> ownerIds = new java.util.ArrayList<>();
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

    private User getCurrentUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));
    }

    private Organization getOrThrow(Long id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id " + id));
    }

    private OrganizationDtos.OrganizationResponse toResponse(Organization organization) {
        OrganizationDtos.OrganizationResponse response = new OrganizationDtos.OrganizationResponse();
        response.setId(organization.getId());
        response.setName(organization.getName());
        User owner = organization.getOwner();
        if (owner != null) {
            response.setOwner(toOwnerSummary(owner));
        } else if (organization.getOwnerId() != null) {
            OrganizationDtos.OwnerSummary summary = new OrganizationDtos.OwnerSummary();
            summary.setId(organization.getOwnerId());
            response.setOwner(summary);
        }
        response.setCategory(organization.getCategory());
        response.setAddress(organization.getAddress());
        response.setEmail(organization.getEmail());
        response.setGoogleCalendarId(organization.getGoogleCalendarId());
        response.setIsActive(organization.getIsActive());
        response.setCreatedAt(organization.getCreatedAt());
        response.setUpdatedAt(organization.getUpdatedAt());
        return response;
    }

    private OrganizationDtos.OwnerSummary toOwnerSummary(User user) {
        if (user == null) {
            return null;
        }
        OrganizationDtos.OwnerSummary summary = new OrganizationDtos.OwnerSummary();
        summary.setId(user.getId());
        summary.setFirstName(user.getFirstName());
        summary.setLastName(user.getLastName());
        summary.setEmail(user.getEmail());
        summary.setRole(user.getRole() != null ? user.getRole().getName().name() : null);
        return summary;
    }

    private OrganizationDtos.OwnerOption toOwnerOption(User user) {
        OrganizationDtos.OwnerOption option = new OrganizationDtos.OwnerOption();
        option.setId(user.getId());
        option.setFirstName(user.getFirstName());
        option.setLastName(user.getLastName());
        option.setEmail(user.getEmail());
        option.setRole(user.getRole() != null ? user.getRole().getName().name() : null);
        return option;
    }

    private User resolveOwner(Long ownerId) {
        if (ownerId == null) {
            return null;
        }
        User user = userRepository.findById(ownerId)
                .orElseThrow(() -> new BadRequestException("Owner user not found with id " + ownerId));
        if (user.getRole() == null || !ALLOWED_OWNER_ROLES.contains(user.getRole().getName())) {
            throw new BadRequestException("Owner must have role SALES or CATEGORY_MANAGER");
        }
        if (Boolean.FALSE.equals(user.getActive())) {
            throw new BadRequestException("Owner must be an active user");
        }
        return user;
    }

    private Organization.OrganizationCategory resolveCategory(Organization.OrganizationCategory category) {
        if (category == null) {
            throw new BadRequestException("Organization category is required");
        }
        return category;
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}


