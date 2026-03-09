package com.fortytwotalents.petclinic.web;

import com.fortytwotalents.petclinic.model.Owner;
import com.fortytwotalents.petclinic.repository.OwnerRepository;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Controller for owner-related operations.
 */
@Controller
@RequestMapping("/owners")
public class OwnerController {

	private final OwnerRepository ownerRepository;

	public OwnerController(OwnerRepository ownerRepository) {
		this.ownerRepository = ownerRepository;
	}

	@GetMapping
	public String listOwners(@RequestParam(required = false, defaultValue = "") String lastName, Model model) {
		List<Owner> owners;
		if (lastName.isBlank()) {
			owners = (List<Owner>) ownerRepository.findAll();
		}
		else {
			owners = ownerRepository.findByLastName(lastName);
		}
		model.addAttribute("owners", owners);
		return "owners/list";
	}

	@GetMapping("/{ownerId}")
	public String showOwner(@PathVariable Integer ownerId, Model model) {
		Owner owner = ownerRepository.findById(ownerId)
			.orElseThrow(() -> new IllegalArgumentException("Invalid owner id: " + ownerId));
		model.addAttribute("owner", owner);
		return "owners/details";
	}

	@GetMapping("/new")
	public String initCreationForm(Model model) {
		model.addAttribute("owner", new Owner());
		return "owners/create-or-update";
	}

	@PostMapping("/new")
	public String processCreationForm(@Valid Owner owner, BindingResult result) {
		if (result.hasErrors()) {
			return "owners/create-or-update";
		}
		ownerRepository.save(owner);
		return "redirect:/owners/" + owner.getId();
	}

}
