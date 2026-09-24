package com.projects.applivo.security;

import com.projects.applivo.entity.RefreshToken;
import com.projects.applivo.entity.User;
import com.projects.applivo.exception.InvalidTokenException;
import com.projects.applivo.repository.RefreshTokenRepository;
import com.projects.applivo.util.HashUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    private final JwtService jwtService;

    public RefreshToken createRefreshToken(User user, String rawToken){

        String tokenHash = HashUtil.sha256(rawToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plusMillis(refreshExpiration))
                .isRevoked(false)
                .build();

        return refreshTokenRepository.save(refreshToken);
    }

    public RefreshToken verify(String rawToken){

        String tokenHash = HashUtil.sha256(rawToken);

        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token."));

        if(token.getExpiresAt().isBefore(Instant.now())){
            throw new InvalidTokenException("Token has expired");
        }

        if(token.getIsRevoked()){
            throw new InvalidTokenException("Token has been revoked. Invalid token");
        }

        return token;

    }

    @Transactional
    public void revoke(String rawToken){
        String tokenHash = HashUtil.sha256(rawToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token."));
        token.setIsRevoked(true);
    }

    @Transactional
    public void revokeAll(User user){
        refreshTokenRepository.revokeAllByUser(user);
    }

    @Transactional
    public String rotate(RefreshToken token){

        token.setIsRevoked(true);
        refreshTokenRepository.save(token);
        String newRawToken = jwtService.generateRefreshToken(token.getUser());

        createRefreshToken(token.getUser(), newRawToken);

        return newRawToken;

    }

}
