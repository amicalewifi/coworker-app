package ch.amicalewifi.repository;

import ch.amicalewifi.model.PrintDeclaration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PrintDeclarationRepository extends JpaRepository<PrintDeclaration, UUID> {
    List<PrintDeclaration> findByMemberIdOrderByDeclaredAtDesc(UUID memberId);
}
