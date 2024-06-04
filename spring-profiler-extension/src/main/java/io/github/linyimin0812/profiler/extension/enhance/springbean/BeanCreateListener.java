package io.github.linyimin0812.profiler.extension.enhance.springbean;

import com.google.gson.Gson;
import io.github.linyimin0812.profiler.api.EventListener;
import io.github.linyimin0812.profiler.api.event.AtEnterEvent;
import io.github.linyimin0812.profiler.api.event.AtExitEvent;
import io.github.linyimin0812.profiler.api.event.Event;
import io.github.linyimin0812.profiler.common.logger.LogFactory;
import io.github.linyimin0812.profiler.common.logger.Logger;
import io.github.linyimin0812.profiler.common.ui.BeanInitResult;
import io.github.linyimin0812.profiler.common.ui.StartupVO;
import io.github.linyimin0812.profiler.common.utils.GsonUtil;
import org.kohsuke.MetaInfServices;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author linyimin
 **/
@MetaInfServices(EventListener.class)
public class BeanCreateListener implements EventListener {

    private final Logger logger = LogFactory.getStartupLogger();

    private final PersistentThreadLocal<Stack<BeanInitResult>> profilerResultThreadLocal = new PersistentThreadLocal<Stack<BeanInitResult>>(Stack::new);

    private final Gson GSON = GsonUtil.create();

    @Override
    public boolean filter(String className) {
        return "org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory".equals(className);
    }

    @Override
    public void onEvent(Event event) {

        if (event.type == Event.Type.AT_ENTER) {
            AtEnterEvent atEnterEvent = (AtEnterEvent) event;
            // 记录bean初始化开始
            String beanName = (String) atEnterEvent.args[0];
            createBeanInitResult(beanName);

        } else if (event.type == Event.Type.AT_EXIT || event.type == Event.Type.AT_EXCEPTION_EXIT) {
            // bean初始化结束, 出栈

            AtExitEvent atExitEvent = (AtExitEvent) event;
            Map<String, String> tags = new HashMap<>();
            tags.put("threadName", Thread.currentThread().getName());
            tags.put("class", atExitEvent.returnObj == null ? null : atExitEvent.returnObj.getClass().getName());
            ClassLoader classLoader = atExitEvent.returnObj == null ? null : atExitEvent.returnObj.getClass().getClassLoader();
            tags.put("classloader", classLoader == null ? "boostrap" : classLoader.getClass().getSimpleName());

            BeanInitResult beanInitResult = profilerResultThreadLocal.get().pop();
            beanInitResult.setTags(tags);
            beanInitResult.duration();
        }
    }

    private void createBeanInitResult(String beanName) {

        BeanInitResult beanInitResult = new BeanInitResult(beanName);

        StartupVO.addBeanInitResult(beanInitResult);

        if (!profilerResultThreadLocal.get().isEmpty()) {

            BeanInitResult parentBeanInitResult = profilerResultThreadLocal.get().peek();
            parentBeanInitResult.addChild(beanInitResult);

        }

        profilerResultThreadLocal.get().push(beanInitResult);
    }

    @Override
    public boolean filter(String methodName, String[] methodTypes) {

        String listenMethodName = "createBean";
        String[] listenMethodTypes = new String[] {
                "java.lang.String",
                "org.springframework.beans.factory.support.RootBeanDefinition",
                "java.lang.Object[]"
        };

        if (!listenMethodName.equals(methodName)) {
            return false;
        }
        if (methodTypes == null || listenMethodTypes.length != methodTypes.length) {
            return false;
        }

        for (int i = 0; i < listenMethodTypes.length; i++) {
            if (!listenMethodTypes[i].equals(methodTypes[i])) {
                return false;
            }
        }

        return true;
    }

    @Override
    public List<Event.Type> listen() {
        return Arrays.asList(Event.Type.AT_ENTER, Event.Type.AT_EXIT);
    }

    @Override
    public void start() {
        logger.info(BeanCreateListener.class, "============BeanCreateListener start=============");
    }

    @Override
    public void stop() {
        logger.info(BeanCreateListener.class, "============BeanCreateListener stop=============");

        List<BeanInitResult> remainInitResult = new ArrayList<>();

        for (Stack<BeanInitResult> stack : profilerResultThreadLocal.getAll()) {
            remainInitResult.addAll(stack);
        }

        if (!remainInitResult.isEmpty()) {
            try {
                logger.warn(BeanCreateListener.class, "profilerResultThreadLocal is not empty. There may be a problem with the initialization of the bean. {}", GSON.toJson(remainInitResult));
            } catch (Throwable ignored) {
                List<String> beanNames = remainInitResult.stream().map(BeanInitResult::getName).collect(Collectors.toList());
                logger.warn(BeanCreateListener.class, "profilerResultThreadLocal is not empty. There may be a problem with the initialization of the bean. {}", GSON.toJson(beanNames));
            }
        }
    }
}
