package dev.zenith.trader;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import dev.zenith.trader.command.VillagerTraderCommand;
import dev.zenith.trader.module.VillagerTrader;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.List;

import static dev.zenith.trader.module.VillagerTrader.VillagerProfession;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "ZenithProxy Villager Trader",
    url = "https://github.com/rfresh2/ZenithProxyVillagerTrader",
    authors = {"rfresh2"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class VillagerTraderPlugin implements ZenithProxyPlugin {
    public static ComponentLogger LOG;
    public static VillagerTraderConfig PLUGIN_CONFIG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        PLUGIN_CONFIG = pluginAPI.registerConfig("villager-trader", VillagerTraderConfig.class);

        backfillProfessions(PLUGIN_CONFIG);

        pluginAPI.registerModule(new VillagerTrader());
        pluginAPI.registerCommand(new VillagerTraderCommand());
    }

    // 为所有职业回填预设信息（中文名/工作台）。仅在字段为空时写入，
    // buyItems 由用户通过 addItem 命令手动配置，不会自动填充。
    private static void backfillProfessions(VillagerTraderConfig cfg) {
        cfg.backfillProfession(VillagerProfession.ARMORER, "盔甲匠", "blast_furnace", "高炉", List.of());
        cfg.backfillProfession(VillagerProfession.WEAPONSMITH, "武器匠", "grindstone", "砂轮", List.of());
        cfg.backfillProfession(VillagerProfession.TOOLSMITH, "工具匠", "smithing_table", "锻造台", List.of());
        cfg.backfillProfession(VillagerProfession.CLERIC, "牧师", "brewing_stand", "酿造台", List.of());
        cfg.backfillProfession(VillagerProfession.LIBRARIAN, "图书管理员", "lectern", "讲台", List.of());
        cfg.backfillProfession(VillagerProfession.FARMER, "农夫", "composter", "堆肥桶", List.of());
        cfg.backfillProfession(VillagerProfession.FISHERMAN, "渔夫", "barrel", "木桶", List.of());
        cfg.backfillProfession(VillagerProfession.BUTCHER, "屠夫", "smoker", "烟熏炉", List.of());
        cfg.backfillProfession(VillagerProfession.FLETCHER, "制箭师", "fletching_table", "制箭台", List.of());
        cfg.backfillProfession(VillagerProfession.LEATHERWORKER, "皮匠", "cauldron", "炼药锅", List.of());
        cfg.backfillProfession(VillagerProfession.CARTOGRAPHER, "制图师", "cartography_table", "制图台", List.of());
        cfg.backfillProfession(VillagerProfession.MASON, "石匠", "stonecutter", "切石机", List.of());
        cfg.backfillProfession(VillagerProfession.SHEPHERD, "牧羊人", "loom", "织布机", List.of());
        cfg.backfillProfession(VillagerProfession.NONE, "无业", "", "", List.of());
        cfg.backfillProfession(VillagerProfession.NITWIT, "傻子", "", "", List.of());
    }
}
