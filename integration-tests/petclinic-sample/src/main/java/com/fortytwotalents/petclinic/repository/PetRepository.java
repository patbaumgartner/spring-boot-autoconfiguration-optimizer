package com.fortytwotalents.petclinic.repository;

import com.fortytwotalents.petclinic.model.Pet;
import org.springframework.data.repository.CrudRepository;

/**
 * Repository for {@link Pet} entities.
 */
public interface PetRepository extends CrudRepository<Pet, Integer> {

}
