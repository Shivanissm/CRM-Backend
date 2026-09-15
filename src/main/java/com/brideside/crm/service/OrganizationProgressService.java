package com.brideside.crm.service;

import com.brideside.crm.dto.OrganizationProgressDtos;

/**
 * Service for computing and updating organization onboarding progress.
 * Progress is tracked across: Organization details, Asset Info, Events Pricing,
 * Vendor Data, Client Data, Team Members. organization.is_active is true when the core-4 activation
 * checklist is complete, or when bootstrap vendor credentials and a default pipeline exist.
 */
public interface OrganizationProgressService {

    /**
     * Recompute progress for an organization and persist to organization_onboarding_progress.
     * Updates organization.is_active to true when all sections are complete.
     */
    void recomputeAndPersistProgress(Long organizationId);

    /**
     * Get the current progress for an organization (computes if not cached).
     */
    OrganizationProgressDtos.ProgressResponse getProgress(Long organizationId);

    /**
     * Debug: same as getProgress but includes raw client data check result.
     */
    OrganizationProgressDtos.ProgressDebugResponse getProgressDebug(Long organizationId);
}
