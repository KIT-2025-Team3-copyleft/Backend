package com.copyleft.GodsChoice.global.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

@Slf4j
@Aspect
@Component
public class LogAspect {

    @Pointcut("execution(* com.copyleft.GodsChoice.feature..*Service.*(..))")
    public void serviceLayer() {}

    @Around("serviceLayer()")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();

        log.info("▶ [START] {}.{} | Args: {}", className, methodName, args);

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable e) {
            log.error("🛑 [EXCEPTION] {}.{} | Msg: {}", className, methodName, e.getMessage());
            throw e;
        }

        stopWatch.stop();
        log.info("◀ [END] {}.{} | Result: {} | Time: {}ms",
                className, methodName, result, stopWatch.getTotalTimeMillis());

        return result;
    }
}