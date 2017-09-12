package wgextender.features.claimcommand;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import wgextender.WGExtender;

import com.sk89q.minecraft.util.commands.CommandException;
import com.sk89q.minecraft.util.commands.CommandPermissionsException;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.bukkit.selections.CuboidSelection;
import com.sk89q.worldedit.bukkit.selections.Polygonal2DSelection;
import com.sk89q.worldedit.bukkit.selections.Selection;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.WorldConfiguration;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.commands.task.RegionAdder;
import com.sk89q.worldguard.bukkit.permission.RegionPermissionModel;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.util.DomainInputResolver.UserLocatorPolicy;

public class WEClaimCommand {

	protected static void claim(String regionname, CommandSender sender) throws CommandException {
		Player player = WGExtender.getWorldGuard().checkPlayer(sender);
		LocalPlayer localPlayer = WGExtender.getWorldGuard().wrapPlayer(player);
		RegionPermissionModel permModel = getPermissionModel(sender);

		if (!permModel.mayClaim()) {
			throw new CommandPermissionsException();
		}

		String id = checkRegionId(regionname, false);

		RegionManager manager = checkRegionManager(WGExtender.getWorldGuard(), player.getWorld());

		checkRegionDoesNotExist(manager, id, false);
		ProtectedRegion region = checkRegionFromSelection(player, id);

		WorldConfiguration wcfg = WGExtender.getWorldGuard().getGlobalStateManager().get(player.getWorld());

		if (!permModel.mayClaimRegionsUnbounded()) {
			int maxRegionCount = wcfg.getMaxRegionCount(player);
			if (maxRegionCount >= 0 && manager.getRegionCountOfPlayer(localPlayer) >= maxRegionCount) {
				throw new CommandException("У вас слишком много регионов, удалите ненужный.");
			}
		}

		ProtectedRegion existing = manager.getRegion(id);

		if (existing != null) {
			if (!existing.getOwners().contains(localPlayer)) {
				throw new CommandException("Такой регион уже существует.");
			}
		}

		ApplicableRegionSet regions = manager.getApplicableRegions(region);

		if (regions.size() > 0) {
			if (!regions.isOwnerOfAll(localPlayer)) {
				throw new CommandException("Это регион пересекается с чужим регионом.");
			}
		} else {
			if (wcfg.claimOnlyInsideExistingRegions) {
				throw new CommandException("Вы можете приватить только внутри своих регионов, которые принадлежат вам или вашей группе.");
			}
		}

		if (wcfg.maxClaimVolume >= Integer.MAX_VALUE) {
			throw new CommandException("Этот регион слишком большой. " + "Максимальный размер: " + Integer.MAX_VALUE);
		}

		if (!permModel.mayClaimRegionsUnbounded()) {
			if (region instanceof ProtectedPolygonalRegion) {
				throw new CommandException("Полигональные регионы не поддерживаются.");
			}

			if (region.volume() > wcfg.maxClaimVolume) {
				player.sendMessage(ChatColor.RED + "Вы не можете заприватить регион такого размера.");
				player.sendMessage(ChatColor.RED + "Максимальный размер: " + wcfg.maxClaimVolume + ", размер твоего региона: " + region.volume());
				return;
			}
		}

		RegionAdder task = new RegionAdder(WGExtender.getWorldGuard(), manager, region);
		task.setLocatorPolicy(UserLocatorPolicy.UUID_ONLY);
		task.setOwnersInput(new String[] { player.getName() });
		try {
			task.call();
			sender.sendMessage(ChatColor.YELLOW + "Новый регион '"+id+"' создан.");
		} catch (Exception e) {
			sender.sendMessage(ChatColor.YELLOW + "Не удалось создать регион '"+id+"'.");
			e.printStackTrace();
		}
	}

	private static String checkRegionId(String id, boolean allowGlobal) throws CommandException {
		if (!ProtectedRegion.isValidId(id)) {
			throw new CommandException("Название региона '" + id + "' содержит запрещённые символы.");
		}

		if (!allowGlobal && id.equalsIgnoreCase("__global__")) {
			throw new CommandException("Вы не можете использовать глобальный регион.");
		}

		return id;
	}

	private static RegionPermissionModel getPermissionModel(CommandSender sender) {
		return new RegionPermissionModel(WorldGuardPlugin.inst(), sender);
	}

	private static RegionManager checkRegionManager(WorldGuardPlugin plugin, World world) throws CommandException {
		if (!plugin.getGlobalStateManager().get(world).useRegions) {
			throw new CommandException("Регионы отключены для данного мира");
		}

		RegionManager manager = plugin.getRegionContainer().get(world);
		if (manager == null) {
			throw new CommandException("Не удалось загрузить регион для данного мира. Пожалуйста, сообщите администратору сервера.");
		}
		return manager;
	}

	private static ProtectedRegion checkRegionFromSelection(Player player, String id) throws CommandException {
		Selection selection = checkSelection(player);
		if (selection instanceof Polygonal2DSelection) {
			Polygonal2DSelection polySel = (Polygonal2DSelection) selection;
			int minY = polySel.getNativeMinimumPoint().getBlockY();
			int maxY = polySel.getNativeMaximumPoint().getBlockY();
			return new ProtectedPolygonalRegion(id, polySel.getNativePoints(), minY, maxY);
		} else if (selection instanceof CuboidSelection) {
			BlockVector min = selection.getNativeMinimumPoint().toBlockVector();
			BlockVector max = selection.getNativeMaximumPoint().toBlockVector();
			return new ProtectedCuboidRegion(id, min, max);
		} else {
			throw new CommandException("Извините, только кубоиды и полигоны могут быть регионами в WorldGuard.");
		}
	}

	private static Selection checkSelection(Player player) throws CommandException {
		WorldEditPlugin worldEdit = WorldGuardPlugin.inst().getWorldEdit();
		Selection selection = worldEdit.getSelection(player);

		if (selection == null) {
			throw new CommandException("Вы не выделили область для привата региона.");
		}

		return selection;
	}

	private static void checkRegionDoesNotExist(RegionManager manager, String id, boolean mayRedefine) throws CommandException {
		if (manager.hasRegion(id)) {
			throw new CommandException("Регион с таким именем уже существует. Пожалуйста, выберите другое имя.");
		}
	}

}
