package com.example.slendercam;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(SlenderCamMod.ID)
public class SlenderCamMod {
    public static final String ID = "slendercam";

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ID);

    public static final RegistryObject<Item> CAMERA = ITEMS.register("camera",
            () -> new Item(new Item.Properties().stacksTo(1).tab(CreativeModeTab.TAB_MISC)));

    public static final RegistryObject<SoundEvent> STATIC_FAR = sound("static_far");
    public static final RegistryObject<SoundEvent> STATIC_MID = sound("static_mid");
    public static final RegistryObject<SoundEvent> STATIC_HEAVY = sound("static_heavy");
    public static final RegistryObject<SoundEvent> STATIC_GLITCH_1 = sound("static_glitch1");
    public static final RegistryObject<SoundEvent> STATIC_GLITCH_2 = sound("static_glitch2");
    public static final RegistryObject<SoundEvent> STATIC_GLITCH_3 = sound("static_glitch3");
    public static final RegistryObject<SoundEvent> GLITCH_SHORT = sound("glitch_short");
    public static final RegistryObject<SoundEvent> BREATHING = sound("breathing");
    public static final RegistryObject<SoundEvent> TELEPORT = sound("teleport");
    public static final RegistryObject<SoundEvent> ZOOM_ON = sound("zoom_on");
    public static final RegistryObject<SoundEvent> ZOOM_OFF = sound("zoom_off");

    private static RegistryObject<SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> new SoundEvent(new ResourceLocation(ID, name)));
    }

    public SlenderCamMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        SOUNDS.register(bus);
    }
}
