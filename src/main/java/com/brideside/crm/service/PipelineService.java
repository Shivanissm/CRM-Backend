package com.brideside.crm.service;

import com.brideside.crm.dto.PipelineDtos;

import java.util.List;

public interface PipelineService {
    PipelineDtos.PipelineResponse createPipeline(PipelineDtos.PipelineRequest request);

    /**
     * Creates a pipeline for a newly created organization (bootstrap). Skips the organization active check
     * since the org cannot be active yet. Used internally when creating the default vendor/pipeline.
     */
    PipelineDtos.PipelineResponse createPipelineForBootstrap(PipelineDtos.PipelineRequest request);
    List<PipelineDtos.PipelineResponse> listPipelines(boolean includeStages);
    List<PipelineDtos.PipelineResponse> listArchivedPipelines(boolean includeStages);
    /**
     * Active pipeline IDs the current user is allowed to see (same rules as listPipelines).
     */
    java.util.Set<Long> getVisiblePipelineIds();
    PipelineDtos.PipelineResponse getPipeline(Long pipelineId, boolean includeStages);
    PipelineDtos.PipelineResponse updatePipeline(Long pipelineId, PipelineDtos.PipelineUpdateRequest request);
    PipelineDtos.PipelineResponse unarchivePipeline(Long pipelineId, boolean includeStages);
    void deletePipeline(Long pipelineId, boolean hardDelete);

    PipelineDtos.StageResponse createStage(Long pipelineId, PipelineDtos.StageRequest request);
    PipelineDtos.StageResponse updateStage(Long pipelineId, Long stageId, PipelineDtos.StageUpdateRequest request);
    void deleteStage(Long pipelineId, Long stageId, boolean hardDelete);
    List<PipelineDtos.StageResponse> listStages(Long pipelineId, boolean includeInactive);
    List<PipelineDtos.StageResponse> reorderStages(Long pipelineId, PipelineDtos.StageOrderRequest request);
    List<PipelineDtos.CategoryOption> listCategoryOptions();
}


