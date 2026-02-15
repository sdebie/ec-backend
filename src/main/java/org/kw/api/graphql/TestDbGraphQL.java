package org.kw.api.graphql;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.math.BigInteger;

/**
 * GraphQL API that exposes a simple query to get the row count of the
 * PostgreSQL table "public.test" used by {@code TestEntity}.
 */
@ApplicationScoped
@GraphQLApi
public class TestDbGraphQL {

    @Query("testRowCount")
    @Description("Returns the number of rows in the 'public.test' table")
    @Transactional(value = TxType.SUPPORTS)
    public long testRowCount() {
        Object single = Panache.getEntityManager()
                .createNativeQuery("SELECT COUNT(*) FROM public.test")
                .getSingleResult();
        if (single instanceof Number n) {
            return n.longValue();
        }
        if (single instanceof BigInteger bi) {
            return bi.longValue();
        }
        // Fallback: try to parse anything else into a number
        return Long.parseLong(String.valueOf(single));
    }
}
