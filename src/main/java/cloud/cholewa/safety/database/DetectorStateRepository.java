package cloud.cholewa.safety.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

//the reads of detector_state; its one write is the upsert of StateStore
public interface DetectorStateRepository extends ReactiveCrudRepository<DetectorStateEntity, Long> {
}
