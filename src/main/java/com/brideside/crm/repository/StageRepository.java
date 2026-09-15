package com.brideside.crm.repository;

import com.brideside.crm.entity.Pipeline;
import com.brideside.crm.entity.Stage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StageRepository extends JpaRepository<Stage, Long> {
    List<Stage> findByPipelineOrderByOrderIndexAsc(Pipeline pipeline);
    List<Stage> findByPipelineAndActiveTrueOrderByOrderIndexAsc(Pipeline pipeline);
    boolean existsByPipelineAndNameIgnoreCase(Pipeline pipeline, String name);
    boolean existsByPipelineAndNameIgnoreCaseAndIdNot(Pipeline pipeline, String name, Long id);
    Optional<Stage> findByIdAndPipeline(Long id, Pipeline pipeline);
    Optional<Stage> findFirstByPipelineAndNameIgnoreCaseAndActiveTrue(Pipeline pipeline, String name);
}



