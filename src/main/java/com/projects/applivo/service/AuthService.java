package com.projects.applivo.service;

import com.projects.applivo.dto.request.LoginRequest;
import com.projects.applivo.dto.request.RefreshTokenRequest;
import com.projects.applivo.dto.request.RegisterRequest;
import com.projects.applivo.dto.response.AuthResponse;
import com.projects.applivo.dto.response.UserInfoResponse;
import com.projects.applivo.entity.RefreshToken;
import com.projects.applivo.entity.Role;
import com.projects.applivo.entity.User;
import com.projects.applivo.exception.DuplicateResourceException;
import com.projects.applivo.repository.UserRepository;
import com.projects.applivo.security.JwtService;
import com.projects.applivo.security.RefreshTokenService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;

    private final JwtService jwtService;

    private final RefreshTokenService refreshTokenService;

    private final TokenBlacklistService blacklistService;

    private final PasswordEncoder passwordEncoder;

    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request){

        if (userRepository.existsByEmail(request.getEmail())){
            throw new DuplicateResourceException("Email already exists.");
        }

        if (userRepository.existsByUsername(request.getUsername())){
            throw new DuplicateResourceException("Username already exists.");
        }

        User user = User.builder()
                .email(request.getEmail())
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole() != null ? request.getRole() : Role.USER)
                .build();

        User savedUser = userRepository.save(user);

        String accessToken = jwtService.generateToken(savedUser);

        String refreshToken = jwtService.generateRefreshToken(savedUser);

        refreshTokenService.createRefreshToken(savedUser, refreshToken);

        return AuthResponse.builder()
                .refreshToken(refreshToken)
                .accessToken(accessToken)
                .userInfo(generateUserInfoResponse(savedUser))
                .build();

    }

    public AuthResponse login(LoginRequest request){
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));
        String accessToken = jwtService.generateToken(user);

        String refreshToken = jwtService.generateRefreshToken(user);

        refreshTokenService.createRefreshToken(user, refreshToken);

        return AuthResponse.builder()
                .refreshToken(refreshToken)
                .accessToken(accessToken)
                .userInfo(generateUserInfoResponse(user))
                .build();
    }

    public AuthResponse refresh(RefreshTokenRequest request){
        RefreshToken existingToken = refreshTokenService.verify(request.getRefreshToken());
        User user = existingToken.getUser();

        String newAccessToken = jwtService.generateToken(user);
        String newRefreshToken = refreshTokenService.rotate(existingToken);
        return AuthResponse.builder()
                .refreshToken(newRefreshToken)
                .accessToken(newAccessToken)
                .userInfo(generateUserInfoResponse(user))
                .build();
    }

    @Transactional
    public void logout(String rawRefreshToken, String accessToken){
        refreshTokenService.revoke(rawRefreshToken);
        blacklistService.blacklist(accessToken);
    }

    @Transactional
    public void logoutAll(User user, String accessToken){
        refreshTokenService.revokeAll(user);
        blacklistService.blacklist(accessToken);
    }

    private UserInfoResponse generateUserInfoResponse(User user){
        return UserInfoResponse.builder()
                .email(user.getEmail())
                .username(user.getUsername())
                .id(user.getId())
                .role(user.getRole())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }


}
