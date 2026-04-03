package com.patbaumgartner.petclinic.repository;

import com.patbaumgartner.petclinic.model.Pet;
import org.springframework.data.repository.CrudRepository;

/**
 * Repository for {@link Pet} entities.
 */
public interface PetRepository extends CrudRepository<Pet, Integer> {

}
