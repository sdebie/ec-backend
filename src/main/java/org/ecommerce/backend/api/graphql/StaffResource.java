package org.ecommerce.backend.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.ecommerce.backend.service.StaffService;
import org.ecommerce.common.dto.StaffDto;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
@GraphQLApi
public class StaffResource {
    @Inject
    StaffService staffService;

    @Query("staffList")
    @Description("Returns a paged list of staff members")
    @Transactional(value = Transactional.TxType.SUPPORTS)
    public List<StaffDto> getStaffList(
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest)
    {
        FilterRequest resolvedFilterRequest = filterRequest != null ? filterRequest : new FilterRequest();

        return staffService.getAllStaffUsers(pageRequest, resolvedFilterRequest);
    }

    @Query("staffCount")
    @Description("Returns the total number of staff matching the given filter.")
    @Transactional(value = Transactional.TxType.SUPPORTS)
    public long staffCount(@Name("filterRequest") FilterRequest filterRequest)
    {
        return staffService.staffCount(filterRequest);
    }

    @Query("staffById")
    @Description("Returns a staff member by ID")
    @Transactional(value = Transactional.TxType.SUPPORTS)
    public StaffDto getStaffById(@Name("id") UUID id)
    {
        return staffService.getStaffById(id);
    }

}
