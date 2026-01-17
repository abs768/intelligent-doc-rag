package com.chatassistant.aichatassistant.service;

import com.chatassistant.aichatassistant.dto.AuthResponse;
import com.chatassistant.aichatassistant.dto.LoginRequest;
import com.chatassistant.aichatassistant.dto.RegisterRequest;
import com.chatassistant.aichatassistant.entity.User;
import com.chatassistant.aichatassistant.exception.BadRequestException;
import com.chatassistant.aichatassistant.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthenticationManager authenticationManager
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
    }

    public AuthResponse register(RegisterRequest request) {
        logger.info("Registration attempt for email: {}", request.email());

        if (userRepository.existsByEmail(request.email())) {
            logger.warn("Registration failed - email already exists: {}", request.email());
            throw new BadRequestException("Email already registered");
        }

        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.name()
        );

        userRepository.save(user);

        logger.info("User registered successfully: {}", request.email());

        String token = jwtService.generateToken(user);

        return new AuthResponse(token, user.getEmail(), user.getName());
    }

    public AuthResponse login(LoginRequest request) {
        logger.info("Login attempt for email: {}", request.email());

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.email(),
                            request.password()
                    )
            );

            User user = userRepository.findByEmail(request.email())
                    .orElseThrow(() -> new BadRequestException("Invalid credentials"));

            String token = jwtService.generateToken(user);

            logger.info("User logged in successfully: {}", request.email());

            return new AuthResponse(token, user.getEmail(), user.getName());

        } catch (Exception e) {
            logger.warn("Login failed for email: {}", request.email());
            throw new BadRequestException("Invalid credentials");
        }
    }
}