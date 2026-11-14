package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仿真的<b>运行时开关</b>（内存态，不落库）。
 *
 * <p>为什么需要它？早期版本里仿真引擎每拍都调用
 * {@code SimulationService.ensureRunning()}：发现没有 RUNNING 的运行就立刻新建一个。
 * 结果就是「停止仿真」按钮<b>完全无效</b> ——
 * 点下去运行被置为 STOPPED，但 1 秒后引擎又建了一个新的 RUNNING 运行继续跑。
 * 用户既停不下来，也就无法"先停下、再手动派单观察效果"。
 *
 * <p>修复思路：把"引擎该不该跑"从一个**数据库查询结果**变成一个**显式的运行时状态**。
 * <ul>
 *   <li>{@code engineActive}：定时推进是否开启。对应界面上的「暂停 / 继续」。</li>
 *   <li>{@code autoDispatchEnabled}：自动扫单派单是否开启。
 *       关掉它，演示者就能手动控制"什么时候派单"，从而看清派单到底做了什么。</li>
 * </ul>
 *
 * <p>为什么不落库？因为它描述的是"当前进程要不要干活"，是进程级状态；
 * 多实例部署时本来就应该由各实例自己决定（或者由调度中心下发）。
 * 重启后回到配置文件里的默认值，这也符合直觉。
 */
@Component
public class SimulationRuntimeState {

    private static final Logger log = LoggerFactory.getLogger(SimulationRuntimeState.class);

    /**
     * 定时推进是否开启。初值恒为 true，与配置项 {@code sky.simulation.enabled} <b>解耦</b>。
     *
     * <p>为什么不直接用配置项当初值？因为两者的语义不同：
     * <ul>
     *   <li>{@code sky.simulation.enabled=false} 表示"这个进程不挂定时器"
     *       —— 集成测试就是这么用的：关掉后台定时器，改用手动步进保证结果确定；</li>
     *   <li>{@code engineActive} 表示"用户是否暂停了推进"，是界面上的暂停/继续按钮。</li>
     * </ul>
     * 如果让前者决定后者，那么"关闭定时器"会连带把手动步进也一起禁掉
     * （测试里就表现为任务 500 拍都推进不动）。
     */
    private final AtomicBoolean engineActive = new AtomicBoolean(true);

    /** 是否允许自动扫单派单。 */
    private final AtomicBoolean autoDispatchEnabled = new AtomicBoolean(true);

    public SimulationRuntimeState(SkyProperties properties) {
        // 保留入参是为了让这个 Bean 依赖配置（未来若要从配置读初值，改这里即可）；
        // 当前初值固定为 true，理由见字段注释。
        log.debug("仿真运行时状态初始化：引擎激活=true，自动派单=true（配置 enabled={}）",
                properties.simulation().enabled());
    }

    public boolean isEngineActive() {
        return engineActive.get();
    }

    public boolean isAutoDispatchEnabled() {
        return autoDispatchEnabled.get();
    }

    /** 恢复推进（对应界面「继续仿真」）。 */
    public void resume(String reason) {
        if (engineActive.compareAndSet(false, true)) {
            log.info("仿真引擎已恢复推进（{}）", reason);
        }
    }

    /** 暂停推进（对应界面「暂停仿真」）。运行记录会保留，手动步进仍然可用。 */
    public void pause(String reason) {
        if (engineActive.compareAndSet(true, false)) {
            log.info("仿真引擎已暂停推进（{}）", reason);
        }
    }

    /** 开关自动派单。 */
    public boolean setAutoDispatch(boolean enabled) {
        autoDispatchEnabled.set(enabled);
        log.info("自动派单已{}", enabled ? "开启" : "关闭（改为手动派单）");
        return enabled;
    }
}
