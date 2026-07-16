package com.pcbuilder.auth.service;

import com.pcbuilder.auth.dto.AuthResponseDto;
import com.pcbuilder.auth.dto.LoginRequest;
import com.pcbuilder.auth.dto.RegisterRequest;
import com.pcbuilder.auth.dto.UserDto;
import com.pcbuilder.auth.entity.User;
import com.pcbuilder.auth.mapper.UserMapper;
import com.pcbuilder.auth.repository.UserRepository;
import com.pcbuilder.exception.DuplicateResourceException;
import com.pcbuilder.security.JwtService;
import com.pcbuilder.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponseDto register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().toLowerCase().trim();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("An account with this email already exists");
        }

        User user = userMapper.toEntity(request);
        User saved = userRepository.save(user);

        UserPrincipal principal = UserPrincipal.fromUser(saved);
        String token = jwtService.generateToken(principal);

        UserDto userDto = userMapper.toDto(saved);
        return new AuthResponseDto(token, userDto);
    }

    public AuthResponseDto login(LoginRequest request) {
        String normalizedEmail = request.getEmail().toLowerCase().trim();

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalizedEmail, request.getPassword())
        );

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException(
                        "Invalid email or password"));

        UserPrincipal principal = UserPrincipal.fromUser(user);
        String token = jwtService.generateToken(principal);

        UserDto userDto = userMapper.toDto(user);
        return new AuthResponseDto(token, userDto);
    }
}
