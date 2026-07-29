package com.projects.applivo.service;

import com.projects.applivo.dto.request.CreateSessionRequest;
import com.projects.applivo.dto.response.SessionResponse;
import com.projects.applivo.dto.response.SessionStatusResponse;
import com.projects.applivo.emulator.EmulatorService;
import com.projects.applivo.entity.*;
import com.projects.applivo.exception.InvalidOperationException;
import com.projects.applivo.exception.ResourceNotFoundException;
import com.projects.applivo.repository.AppRepository;
import com.projects.applivo.repository.AppVersionRepository;
import com.projects.applivo.repository.EmulatorInstanceRepository;
import com.projects.applivo.repository.SessionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;

    private final AppVersionRepository appVersionRepository;

    private final EmulatorService emulatorService;

    private final EmulatorInstanceRepository emulatorInstanceRepository;

    private final AppRepository appRepository;

    public SessionResponse createSession(CreateSessionRequest request, User user){

        AppVersion appVersion = appVersionRepository.findById(request.getAppVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("App version not found."));

        if (!appVersion.getIsActive()){
            throw new InvalidOperationException("App version is not active.");
        }

        boolean hasActiveSession = sessionRepository
                .existsByUserAndSessionStatusIn(user, List.of(SessionStatus.ACTIVE, SessionStatus.CREATING));

        if (hasActiveSession){
            throw new InvalidOperationException("Active session exists for user!");
        }

        Session session = Session.builder()
                .appVersion(appVersion)
                .user(user)
                .sessionStatus(SessionStatus.CREATING)
                .lastActivityAt(Instant.now())
                .build();

        Session saved = sessionRepository.save(session);

        try{
            emulatorService.startEmulatorAsync(saved.getId(), appVersion.getId());
        }catch (TaskRejectedException e){
            saved.setSessionStatus(SessionStatus.FAILED);
            sessionRepository.save(saved);
            throw new InvalidOperationException("Server is at max capacity. Try again later!");
        }

        return SessionResponse.builder()
                .sessionId(saved.getId())
                .appVersionId(appVersion.getId())
                .status(saved.getSessionStatus().name())
                .wsEndpoint("we://localhost:8080/ws")
                .subscriptionTopic("/topic/demo/" + saved.getId())
                .startedAt(saved.getStartedAt())
                .build();

    }

    public SessionStatusResponse getSessionStatus(Long sessionId, User user){

        Session session = getOwnedSession(sessionId, user);

        Integer screenWidth = null;
        Integer screenHeight = null;

        if (session.getSessionStatus() == SessionStatus.ACTIVE && session.getEmulatorContainerId() != null){
            EmulatorInstance emulator = emulatorInstanceRepository
                    .findByContainerId(session.getEmulatorContainerId())
                    .orElse(null);
            if (emulator != null){
                screenWidth = emulator.getScreenWidth();
                screenHeight = emulator.getScreenHeight();
            }
        }

        String message = (session.getSessionStatus() == SessionStatus.FAILED
                && session.getFailureReason() != null
                && !session.getFailureReason().isBlank())
                ? session.getFailureReason()
                : resolveMessage(session.getSessionStatus());

        return SessionStatusResponse.builder()
                .sessionId(session.getId())
                .status(session.getSessionStatus().name())
                .message(message)
                .screenHeight(screenHeight)
                .screenWidth(screenWidth)
                .build();
    }

    @Transactional
    public void endSession(Long sessionId, User user){

        Session session = getOwnedSession(sessionId, user);

        if (session.getSessionStatus() == SessionStatus.ENDED || session.getSessionStatus() == SessionStatus.FAILED){
            return;
        }

        if (session.getEmulatorContainerId() != null){
            emulatorService.stopEmulator(session.getEmulatorContainerId());
        }

        session.setSessionStatus(SessionStatus.ENDED);
        session.setEndedAt(Instant.now());
        sessionRepository.save(session);

    }

    @Transactional
    public void updateLastActivity(Long sessionId){

        Session session = sessionRepository.getSessionById(sessionId);

        if (session != null){
            session.setLastActivityAt(Instant.now());
            sessionRepository.save(session);
        }

    }



    private Session getOwnedSession(Long sessionId, User user){
        return sessionRepository.findByIdAndUser(sessionId, user)
                .orElseThrow(()-> new ResourceNotFoundException("Session not found."));
    }

    private String resolveMessage(SessionStatus sessionStatus){
        return switch (sessionStatus){
            case CREATING ->
                "Emulator is starting, please wait....";
            case ACTIVE ->
                "Session is ready.";
            case FAILED ->
                    "Session failed to start.";
            case ENDED ->
                    "Session has ended";
            case TIMED_OUT ->
                    "Session timed out due to inactivity";
        };
    }

}
