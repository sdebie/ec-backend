package org.ecommerce.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mapping the "public.test" table.
 * Also provides a small utility method to check if the table is available.
 */
@Entity
@Table(name = "test")
public class TestEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    public UUID id;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "description")
    public String description;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;

    /**
     * Checks whether the mapped table exists and is accessible using a simple native query.
     *
     * This performs the same check used previously in the REST resource but centralizes
     * the DB logic close to the mapping.
     */
    @Transactional(value = TxType.SUPPORTS)
    public static boolean isTableAvailable() {
        String sql = "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'test')";
        Object singleResult = Panache.getEntityManager()
                .createNativeQuery(sql)
                .getSingleResult();
        // Depending on JDBC driver, result can be Boolean or Number
        if (singleResult instanceof Boolean b) {
            return b;
        }
        if (singleResult instanceof Number n) {
            return n.intValue() != 0;
        }
        return false;
    }
}
