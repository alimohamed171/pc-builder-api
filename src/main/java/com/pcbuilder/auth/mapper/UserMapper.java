package com.pcbuilder.auth.mapper;

import com.pcbuilder.auth.dto.RegisterRequest;
import com.pcbuilder.auth.dto.UserDto;
import com.pcbuilder.auth.entity.Role;
import com.pcbuilder.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Dedicated mapper for User <-> DTO conversions.
 * Kept separate from the service layer on purpose.
 */
@Component
@RequiredArgsConstructor
public class UserMapper {

    private final PasswordEncoder passwordEncoder;

    public User toEntity(RegisterRequest request) {
        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail().toLowerCase().trim());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.USER);
        return user;
    }

    public UserDto toDto(User user) {
        return new UserDto(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole().name()
        );
    }
}
