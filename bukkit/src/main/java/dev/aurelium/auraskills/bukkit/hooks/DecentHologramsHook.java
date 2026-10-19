package dev.aurelium.auraskills.bukkit.hooks;

import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.common.hooks.Hook;
import dev.aurelium.auraskills.common.scheduler.TaskRunnable;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.DecentHologramsAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Iterator;

public class DecentHologramsHook extends HologramsHook {

    private final AuraSkills plugin;

    // Rede de segurança contra hologramas de dano "presos" que não deveriam durar
    // mais que ~1.5s (30 ticks). O delete normal é agendado via scheduleAtLocation,
    // mas se por qualquer motivo (lag, dessincronia do DecentHolograms com o cliente,
    // etc.) esse agendamento não rodar, o holograma fica preso pra sempre. Essa
    // varredura periódica força a remoção de qualquer um que passe muito do tempo
    // de vida esperado, independente da causa.
    private final Map<String, Hologram> trackedHolograms = new ConcurrentHashMap<>();
    private final Map<String, Long> creationTimes = new ConcurrentHashMap<>();
    private static final long MAX_LIFETIME_MS = 10_000L;

    private static final String NAME_PREFIX = "AureliumSkills_";

    public DecentHologramsHook(AuraSkills plugin, ConfigurationNode config) {
        super(plugin, config);
        this.plugin = plugin;
        cleanupLeftoverHolograms();
        startOrphanSweeper();
    }

    /**
     * Os hologramas de dano são criados com save=false e removidos ~1.5s depois via
     * tarefa agendada - mas se o servidor reiniciar (ou crashar) nesse intervalo, a
     * tarefa de remoção nunca roda, e o DecentHolograms pode carregar esse holograma
     * "em voo" de volta do disco no próximo boot, deixando ele preso pra sempre até
     * alguém apagar manualmente. Varre o registro do DecentHolograms assim que esse
     * hook é criado (depois que o DecentHolograms já carregou os hologramas salvos)
     * e remove qualquer sobra com o nosso prefixo antes que algum jogador veja.
     */
    private void cleanupLeftoverHolograms() {
        List<String> leftover = new ArrayList<>();
        for (String name : DecentHologramsAPI.get().getHologramManager().getHologramNames()) {
            if (name.startsWith(NAME_PREFIX)) {
                leftover.add(name);
            }
        }
        for (String name : leftover) {
            DHAPI.removeHologram(name);
        }
    }

    @Override
    public void createHologram(Location location, String text, Player player) {
        String name = NAME_PREFIX + UUID.randomUUID();
        Hologram hologram = DHAPI.createHologram(name, location, false, Collections.singletonList(text));
        trackedHolograms.put(name, hologram);
        creationTimes.put(name, System.currentTimeMillis());
        deleteHologram(hologram, name);
    }

    public void deleteHologram(Hologram dh, String name) {
        plugin.getScheduler().scheduleAtLocation(dh.getLocation(), () -> {
            dh.delete();
            trackedHolograms.remove(name);
            creationTimes.remove(name);
        }, 30L * 50L, TimeUnit.MILLISECONDS);
    }

    private void startOrphanSweeper() {
        plugin.getScheduler().timerAsync(new TaskRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<String, Long>> it = creationTimes.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Long> entry = it.next();
                    if (now - entry.getValue() > MAX_LIFETIME_MS) {
                        Hologram orphan = trackedHolograms.remove(entry.getKey());
                        it.remove();
                        if (orphan != null) {
                            orphan.delete();
                        }
                    }
                }
            }
        }, 10L * 1000L, 10L * 1000L, TimeUnit.MILLISECONDS);
    }

    @Override
    public Class<? extends Hook> getTypeClass() {
        return HologramsHook.class;
    }

}
