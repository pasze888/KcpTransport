package io.github.pasze888.kcptransport;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * KCP transport for Minecraft.
 *
 * <p>Replaces the TCP socket underneath the vanilla Netty pipeline with KCP over UDP,
 * leaving everything above the byte-stream channel untouched.
 */
@Mod(KcpTransport.MODID)
public class KcpTransport {
    public static final String MODID = "kcptransport";
    public static final Logger LOGGER = LogUtils.getLogger();

    public KcpTransport(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("KCP Transport loaded");
    }
}
