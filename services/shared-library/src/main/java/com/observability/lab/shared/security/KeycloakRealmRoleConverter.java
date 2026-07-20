package com.observability.lab.shared.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps the realm roles inside a Keycloak access token to Spring Security authorities.
 *
 * <p>Keycloak carries realm roles in a nested {@code realm_access.roles} claim:
 *
 * <pre>{@code
 * "realm_access": { "roles": ["USER", "ADMIN", "offline_access"] }
 * }</pre>
 *
 * <p>Each becomes a {@code ROLE_}-prefixed authority, because that prefix is the contract
 * {@code hasRole("ADMIN")} and {@code hasAuthority("ROLE_ADMIN")} both rely on — {@code hasRole} adds
 * the prefix and compares, so a converter that emitted a bare {@code ADMIN} would silently authorize
 * nobody.
 *
 * <p>Client roles ({@code resource_access.<client>.roles}) are intentionally ignored. This lab
 * authorizes on realm roles only; folding client roles in as well would make the same role name mean
 * two different things depending on which client minted the token.
 */
public final class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccess = jwt.getClaim(REALM_ACCESS);
        if (!(realmAccess instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object roles = map.get(ROLES);
        if (!(roles instanceof Collection<?> roleList)) {
            return List.of();
        }
        return roleList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .collect(Collectors.toUnmodifiableList());
    }
}
