package com.fortytwotalents.petclinic.repository;

import com.fortytwotalents.petclinic.model.Owner;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

/**
 * Repository for {@link Owner} entities.
 */
public interface OwnerRepository extends CrudRepository<Owner, Integer> {

	@Cacheable("owners")
	List<Owner> findByLastName(String lastName);

}
