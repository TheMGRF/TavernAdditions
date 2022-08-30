package net.tavern.tavernadditions.command;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.tavern.tavernadditions.TavernAdditions;

@Mod.EventBusSubscriber(modid = TavernAdditions.MOD_ID)
public class CommandRegister {

    @SubscribeEvent
    public static void onCommandsRegister(RegisterCommandsEvent event) {
        TPCCommand.register(event.getDispatcher());
    }

}
