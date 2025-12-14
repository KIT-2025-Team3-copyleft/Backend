package com.copyleft.GodsChoice.global.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.util.Arrays;

@Slf4j
@Aspect
@Component
public class LogAspect {

    @Pointcut("execution(* com.copyleft.GodsChoice..service.*Service.*(..))")
    public void serviceLayer() {}

    @Around("serviceLayer()")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();

        log.info("▶ [START] {}.{} | Args: {}", className, methodName, Arrays.deepToString(args));

        Object result = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            log.error("🛑 [EXCEPTION] {}.{} | Msg: {}", className, methodName, e.getMessage(), e);
            throw e;
        } finally {
            if (stopWatch.isRunning()) {
                stopWatch.stop();
            }
            log.info("◀ [END] {}.{} | Result: {} | Time: {}ms",
                    className, methodName, result, stopWatch.getTotalTimeMillis());
        }
    }
}