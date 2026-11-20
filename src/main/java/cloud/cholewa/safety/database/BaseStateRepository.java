package cloud.cholewa.safety.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

//the reads of base_state; its one write is the upsert of StateStore
public interface BaseStateRepository extends ReactiveCrudRepository<BaseStateEntity, String> {
}
