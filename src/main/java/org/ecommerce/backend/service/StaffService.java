package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import io.quarkus.elytron.security.common.BcryptUtil;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.mapper.StaffMapper;
import org.ecommerce.common.dto.StaffDto;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.exception.StaffAlreadyExistsException;
import org.ecommerce.common.exception.StaffNotFoundException;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.StaffRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@ApplicationScoped
public class StaffService {
    @Inject
    StaffRepository staffRepository;

    @Inject
    StaffMapper staffMapper;

    public List<StaffDto> getAllStaffUsers(PageRequest pageRequest, FilterRequest filterRequest)
    {
        List<StaffUserEntity> staffEntities = staffRepository.findAll(pageRequest, filterRequest);
        return staffMapper.mapEntityToDto(staffEntities);
    }

    public long staffCount(FilterRequest filterRequest)
    {
        return staffRepository.count(filterRequest);
    }

    public StaffDto getStaffById(UUID id)
    {
        if (id == null) {
            throw new IllegalArgumentException("Staff id is null");
        }

        StaffUserEntity staffEntity = staffRepository.findById(id);
        if (staffEntity == null) {
            throw new StaffNotFoundException("Staff id " + id + " not found");
        }

        return staffMapper.mapEntityToDto(staffEntity);
    }

    @Transactional
    public void createStaffUser(StaffDto staffDto)
    {
        try {
            if (validateFields(staffDto)) {
                if (staffDto.getTemporaryPassword() == null || staffDto.getTemporaryPassword().isBlank()) {
                    throw new IllegalArgumentException("Staff temporary password is required");
                }

                if (staffDto.getId() != null) {
                    StaffUserEntity existing = staffRepository.findById(staffDto.getId());
                    if (existing != null) {
                        throw new StaffAlreadyExistsException("Staff user with id " + staffDto.getId() + " already exists");
                    }
                }

                if (staffRepository.findByEmailExcludingId(staffDto.getEmail(), null) != null) {
                    throw new StaffAlreadyExistsException("Staff user with email '" + staffDto.getEmail() + "' already exists");
                }

                StaffUserEntity staffEntity = staffMapper.mapDtoToEntity(staffDto, new StaffUserEntity());
                staffEntity.passwordHash = BcryptUtil.bcryptHash(staffDto.getTemporaryPassword());
                staffEntity.resetPassword = true;
                staffEntity.createdAt = LocalDateTime.now();
                staffRepository.persist(staffEntity);
            }
        } catch (StaffNotFoundException | StaffAlreadyExistsException e) {
            log.warn("Staff create operation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error creating staff user: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public void updateStaffUser(UUID id, StaffDto staffDto)
    {
        try {
            if (validateFields(staffDto)) {
                staffDto.setId(id);

                StaffUserEntity staffEntity = staffRepository.findById(id);
                if (staffEntity == null) {
                    throw new StaffNotFoundException("Staff user with id " + id + " not found");
                }

                if (staffRepository.findByEmailExcludingId(staffDto.getEmail(), id) != null) {
                    throw new StaffAlreadyExistsException("Staff user with email '" + staffDto.getEmail() + "' already exists");
                }

                // Preserve immutable audit data before DTO mapping.
                var createdAt = staffEntity.createdAt;
                staffMapper.mapDtoToEntity(staffDto, staffEntity);

                // Never allow update payloads to overwrite the original create timestamp.
                staffEntity.createdAt = createdAt;

                staffEntity.resetPassword = staffDto.isResetPassword();

                if (staffDto.getTemporaryPassword() != null && !staffDto.getTemporaryPassword().isBlank()) {
                    staffEntity.passwordHash = BcryptUtil.bcryptHash(staffDto.getTemporaryPassword());
                    staffEntity.resetPassword = true;
                }

                staffRepository.persist(staffEntity);
            }
        } catch (StaffNotFoundException | StaffAlreadyExistsException e) {
            log.warn("Staff update operation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error updating staff user: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public void resetStaffPassword(String email, String password)
    {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Staff email is null");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password is null");
        }

        StaffUserEntity staffEntity = StaffUserEntity.findByEmail(email);
        if (staffEntity == null) {
            throw new StaffNotFoundException("Staff user with email '" + email + "' not found");
        }

        staffEntity.passwordHash = BcryptUtil.bcryptHash(password);
        staffEntity.resetPassword = false;
        staffRepository.persist(staffEntity);
    }

    private boolean validateFields(StaffDto staffDto)
    {
        if (staffDto == null) {
            throw new IllegalArgumentException("StaffDto is null");
        }


        if (staffDto.getEmail() == null) {
            throw new IllegalArgumentException("Staff email is null");
        }

        if (staffDto.getRole() == null) {
            throw new IllegalArgumentException("Staff role is null");
        }

        return true;
    }
}

