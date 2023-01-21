package asoxus.lotteryplus;

import net.milkbowl.vault.economy.Economy;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.*;
import java.util.logging.Logger;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class LotteryPlus extends JavaPlugin {
    private Economy economy = null;
    private Balance balance = new Balance();
    private double ticketPrice;
    private int maxTicketCount;
    private double lotteryPot;
    private double chanceWin;
    private int chancePercent;
    private List<Player> lotteryTicketHolders = new ArrayList<>();
    private boolean isDrawing = false;
    private String lastWinnerName = "";
    private String lastDrawTime;
    private double lastWinnerAmount;
    private String header = "";
    private String footer = "";
    private String prefix = "";
    private Logger logger;
    private int Interval;
    private int lotteryDrawTaskId;
    private int lotteryAnnounceTaskId;

    private Map<Player, Long> chanceCommandCooldown = new HashMap<>();
    private int chanceCommandCooldownTaskId;

    public String formatCurrency(double amount) {
        NumberFormat formatter = NumberFormat.getNumberInstance();
        formatter.setMinimumFractionDigits(2);
        formatter.setMaximumFractionDigits(2);
        return "$" + formatter.format(amount);
    }

    @Override
    public void onEnable() {
        // This code runs when the plugin is enabled
        if (!setupEconomy()) {
            getLogger().severe("Vault not found, disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        ticketPrice = config.getDouble("ticket-price", 2.5);
        maxTicketCount = config.getInt("max-ticket-count", 100);
        chanceWin = config.getDouble("chance-win", 2);
        chancePercent = config.getInt("chance-percent", 20);
        header = ChatColor.translateAlternateColorCodes('&', config.getString("header"));
        footer = ChatColor.translateAlternateColorCodes('&', config.getString("footer"));
        prefix = ChatColor.translateAlternateColorCodes('&', config.getString("prefix"));
        lotteryPot = 0.0;
        Interval = config.getInt("interval", 60);

        logger = Logger.getLogger("Minecraft");
        logger.info("[LotteryPlus] has been enabled!");

        scheduleLotteryDraw();
        scheduleLotteryAnnounce();

        chanceCommandCooldown = new HashMap<>();
        chanceCommandCooldownTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                Iterator<Map.Entry<Player, Long>> iterator = chanceCommandCooldown.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Player, Long> entry = iterator.next();
                    if (System.currentTimeMillis() - entry.getValue() >= 60000) {
                        iterator.remove();
                    }
                }
            }
        }, 0L, 20L * 60);
    }

    public void onDisable() {
        //save the data.yml file
        FileConfiguration dataConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "data.yml"));
        try {
            dataConfig.save(new File(getDataFolder(), "data.yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        //cancel the lottery draw task
        Bukkit.getScheduler().cancelTask(lotteryDrawTaskId);
        Bukkit.getScheduler().cancelTask(lotteryAnnounceTaskId);
        Bukkit.getScheduler().cancelTask(chanceCommandCooldownTaskId);
        //log that the plugin was disabled
        getLogger().info("LotteryPlus has been disabled!");
    }

    public void scheduleLotteryAnnounce() {
        lotteryAnnounceTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                if (!isDrawing) {
                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage(prefix + "&aThe current lottery pot is " + ChatColor.BOLD + ChatColor.GOLD + formatCurrency(lotteryPot) + ChatColor.RESET + ChatColor.GREEN + " with " + lotteryTicketHolders.size() + " tickets!");
                    Bukkit.broadcastMessage(prefix + "&aBuy your tickets with " + ChatColor.BOLD + ChatColor.GOLD + "/lottery buy <amount>");
                    Bukkit.broadcastMessage(footer);
                }
            }
        }, 20 * Interval, 20 * Interval);
    }

    public void scheduleLotteryDraw() {
        lotteryDrawTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                isDrawing = true;
                if (lotteryTicketHolders.size() > 0) {
                    Random random = new Random();
                    Player winner = lotteryTicketHolders.get(random.nextInt(lotteryTicketHolders.size()));
                    lastWinnerName = winner.getName();
                    lastWinnerAmount = lotteryPot;
                    lastDrawTime = String.valueOf(new Date().getTime());

                    saveWinnerData(winner, lotteryPot);

                    balance.setBalance(winner, balance.getBalance(winner) + lotteryPot);
                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage(prefix + ChatColor.GREEN + "Congratulations! " + ChatColor.BOLD + ChatColor.GOLD + winner.getName() + ChatColor.RESET + ChatColor.GREEN + " has won the lottery and won " + ChatColor.BOLD + ChatColor.GOLD + formatCurrency(lotteryPot) + ChatColor.RESET + ChatColor.GREEN + "!");
                    Bukkit.broadcastMessage(footer);
                    lotteryTicketHolders.clear();
                    lotteryPot = 0.0;
                } else {
                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage(prefix + ChatColor.GREEN + "No one has bought lottery tickets, so no one won the lottery this round.");
                    Bukkit.broadcastMessage(footer);
                    lotteryPot = 0.0;
                }
                isDrawing = false;
            }
        }, 20 * 60 * 60, 20 * 60 * 60);
    }

    private void saveWinnerData(Player winner, double prize) {
        FileConfiguration data = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "data.yml"));
        data.set("last-winner", winner.getName());
        data.set("last-prize", prize);
        data.set("last-draw-time", new Date().getTime());

        try {
            data.save(new File(getDataFolder(), "data.yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String,Object> getLastDrawData() {
        Map<String,Object> data = new HashMap<>();
        FileConfiguration dataConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "data.yml"));
        data.put("last-winner", dataConfig.getString("last-winner"));
        data.put("last-prize", dataConfig.getDouble("last-prize"));
        data.put("last-draw-time", dataConfig.getLong("last-draw-time"));
        return data;
    }

    private long getTimeUntilNextDraw() {
        long lastDrawTime = (long) getLastDrawData().get("last-draw-time");
        getLogger().info("Last draw time " + lastDrawTime);

        long currentTime = System.currentTimeMillis();
        getLogger().info("Current time " + currentTime);

        long timeUntilNextDraw = (lastDrawTime + Interval/60*60*1000 - currentTime) / (1000*60);
        getLogger().info("Time until draw " + timeUntilNextDraw);

        return timeUntilNextDraw;
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    public class Balance {
        public double getBalance(Player player) {
            return economy.getBalance(player);
        }

        public boolean setBalance(Player player, double amount) {
            return economy.withdrawPlayer(player, economy.getBalance(player)).transactionSuccess()
                    && economy.depositPlayer(player, amount).transactionSuccess();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("lottery")) {
            if (args.length == 0) {
                sender.sendMessage(prefix + ChatColor.RED + "Invalid command. Usage: /lottery <buy | donate | chance | announce>");
                return true;
            }
            if (args[0].equalsIgnoreCase("buy")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("This command can only be executed by a player.");
                    return true;
                }
                Player player = (Player) sender;
                if (args.length != 2) {
                    player.sendMessage(prefix + ChatColor.GREEN + "Usage: /lottery buy <number of tickets>");
                    return true;
                }
                int ticketCount;
                try {
                    ticketCount = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage(prefix + ChatColor.RED + "Invalid number of tickets.");
                    return true;
                }
                if (ticketCount < 1 || ticketCount > maxTicketCount) {
                    player.sendMessage(prefix + ChatColor.RED + "You can only purchase between 1 and " + maxTicketCount + " lottery tickets.");
                    return true;
                }
                double totalPrice = ticketCount * ticketPrice;
                if (balance.getBalance(player) < totalPrice) {
                    player.sendMessage(prefix + ChatColor.RED + "You don't have enough money to purchase " + ticketCount + " lottery tickets.");
                    return true;
                }
                balance.setBalance(player, balance.getBalance(player) - totalPrice);
                lotteryPot += totalPrice;
                for (int i = 0; i < ticketCount; i++) {
                    lotteryTicketHolders.add(player);
                }
                player.sendMessage(prefix + ChatColor.GREEN + "You have successfully purchased " + ChatColor.GOLD + ticketCount + ChatColor.GREEN + " lottery tickets for " + ChatColor.GOLD + formatCurrency(totalPrice));
                Bukkit.broadcastMessage(header);
                Bukkit.broadcastMessage(prefix + ChatColor.GOLD + player.getName() + ChatColor.GREEN + " has purchased " + ChatColor.GOLD + ticketCount + ChatColor.GREEN + " /lottery tickets. There is now " + ChatColor.GOLD + formatCurrency(lotteryPot) + ChatColor.GREEN + " in the pot!");
                Bukkit.broadcastMessage(footer);
                return true;
            } else if (args[0].equalsIgnoreCase("donate")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(prefix + ChatColor.RED + "This command can only be executed by a player.");
                    return true;
                }
                Player player = (Player) sender;
                if (args.length != 2) {
                    player.sendMessage(prefix + ChatColor.GREEN + "Usage: /lottery donate <amount>");
                    return true;
                }
                double donationAmount;
                try {
                    donationAmount = Double.parseDouble(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage(prefix + ChatColor.RED + "Invalid donation amount.");
                    return true;
                }
                if (donationAmount < 0) {
                    player.sendMessage(prefix + ChatColor.RED + "Donation amount must be greater than or equal to 0.");
                    return true;
                }
                if (balance.getBalance(player) < donationAmount) {
                    player.sendMessage(prefix + ChatColor.RED + "You don't have enough money to donate " + formatCurrency(donationAmount) + ".");
                    return true;
                }
                balance.setBalance(player, balance.getBalance(player) - donationAmount);
                lotteryPot += donationAmount;
                player.sendMessage(prefix + ChatColor.GREEN + "You have successfully donated " + ChatColor.GOLD + formatCurrency(donationAmount) + ChatColor.GREEN + " to the lottery pot.");
                Bukkit.broadcastMessage(header);
                Bukkit.broadcastMessage(prefix + ChatColor.GOLD + player.getName() + ChatColor.GREEN + " has donated " + ChatColor.GOLD + formatCurrency(donationAmount) + ChatColor.GREEN + " to the lottery pot. There is now " + ChatColor.GOLD + formatCurrency(lotteryPot) + ChatColor.GREEN + " in the pot!");
                Bukkit.broadcastMessage(footer);
                return true;
            } else if (args[0].equalsIgnoreCase("chance")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("This command can only be executed by a player.");
                    return true;
                }
                Player player = (Player) sender;
                if (args.length != 2) {
                    player.sendMessage(prefix + ChatColor.RED + "Usage: /lottery chance <amount>");
                    return true;
                }
                double stakeAmount;
                try {
                    stakeAmount = Double.parseDouble(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage(prefix + ChatColor.RED + "Invalid stake amount.");
                    return true;
                }
                if (stakeAmount <= 0) {
                    player.sendMessage(prefix + ChatColor.RED + "Stake amount must be greater than 0.");
                    return true;
                }
                if (balance.getBalance(player) < stakeAmount) {
                    player.sendMessage(prefix + ChatColor.RED + "You don't have enough money to stake " + formatCurrency(stakeAmount) + ".");
                    return true;
                }
                balance.setBalance(player, balance.getBalance(player) - stakeAmount);
                Random random = new Random();
                int chance = random.nextInt(100);

                logger.info("[LotteryPlus] " + player.getName() + "has risked " + stakeAmount);
                logger.info("[LotteryPlus] The chance result is " + chance + ", and the chancePercent is " + chancePercent);

                if (chance <= chancePercent) {
                    double winnings = stakeAmount * chanceWin;
                    balance.setBalance(player, balance.getBalance(player) + winnings);
                    player.sendMessage(prefix + ChatColor.GREEN + "Congratulations! You won " + ChatColor.GOLD + formatCurrency(winnings) + ChatColor.GREEN + " in the lottery chance game!");
                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage(prefix + ChatColor.GOLD + player.getName() + ChatColor.GREEN + " has chanced " + ChatColor.GOLD + formatCurrency(stakeAmount) + ChatColor.GREEN + " and won " + ChatColor.GOLD + formatCurrency(winnings) + ChatColor.GREEN + "! Take the chance yourself with /lottery chance <amount>!");
                    Bukkit.broadcastMessage(footer);
                } else {
                    player.sendMessage(prefix + ChatColor.GREEN + "Sorry, you did not win this time. Better luck next time!");
                    balance.setBalance(player, balance.getBalance(player) - stakeAmount);
                    lotteryPot += stakeAmount;
                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage(prefix + ChatColor.GOLD + player.getName() + " has risked " + ChatColor.GOLD + formatCurrency(stakeAmount) + ChatColor.GREEN + " and lost it all! The lottery amount is now " + ChatColor.GOLD + formatCurrency(lotteryPot) + "!");
                    Bukkit.broadcastMessage(footer);
                }
                return true;
            } else if (args[0].equalsIgnoreCase("announce")) {

                Player player = (Player) sender;
                if (!player.hasPermission("lotteryplus.lottery.announce")) {
                    player.sendMessage(prefix + ChatColor.RED +"You do not have permission to execute this command.");
                    return true;
                }

                if (!(sender instanceof Player)) {
                    sender.sendMessage("This command can only be executed by a player.");
                    return true;
                }
                Bukkit.broadcastMessage(header);
                Bukkit.broadcastMessage(prefix + ChatColor.GREEN + "The current lottery pot is "+ ChatColor.BOLD + ChatColor.GOLD + formatCurrency(lotteryPot) + ChatColor.RESET + ChatColor.GREEN + "!");
                Bukkit.broadcastMessage(prefix + ChatColor.GREEN + "Buy your tickets with "+ ChatColor.BOLD + ChatColor.GOLD +"/lottery buy <amount>");
                Bukkit.broadcastMessage(footer);
                return true;

            } else if (args[0].equalsIgnoreCase("status")) {

                Player player = (Player) sender;
                if (!player.hasPermission("lotteryplus.lottery.status")) {
                    player.sendMessage("You do not have permission to execute this command.");
                    return true;
                }
                player.sendMessage(header);
                if( lastWinnerName != "" ){
                    Map<String,Object> data = getLastDrawData();
                    String lastWinner = (String) data.get("last-winner");
                    double lastPrize = (double) data.get("last-prize");

                    player.sendMessage(prefix + ChatColor.GREEN + "Last lottery winner: " + lastWinner);
                    player.sendMessage(prefix + ChatColor.GREEN + "Amount won: " + formatCurrency(lastPrize));
                }
                player.sendMessage(prefix + "&aNumber of tickets in current lottery: " + lotteryTicketHolders.size());
                player.sendMessage(prefix + "&bCurrent prize pot: " + ChatColor.GOLD + formatCurrency(lotteryPot));
                player.sendMessage(prefix + "&cThe next draw will be " + getTimeUntilNextDraw());
                player.sendMessage(footer);
                return true;

            } else if (args[0].equalsIgnoreCase("boost")) {

                Player player = (Player) sender;
                if (!player.hasPermission("lotteryplus.lottery.boost")) {
                    player.sendMessage("You do not have permission to execute this command.");
                    return true;
                }

                double boostAmount;
                try {
                    boostAmount = Double.parseDouble(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage(prefix + ChatColor.RED + "Invalid boost amount.");
                    return true;
                }
                if (boostAmount <= 0) {
                    player.sendMessage(prefix + ChatColor.RED + "Boost amount must be greater than 0.");
                    return true;
                }
                lotteryPot += boostAmount;
                Bukkit.broadcastMessage(header);
                Bukkit.broadcastMessage(prefix + ChatColor.GOLD + player.getName() + ChatColor.GREEN + " has boosted the lottery by " + ChatColor.BOLD + ChatColor.GOLD + formatCurrency(boostAmount) + ChatColor.RESET + ChatColor.GREEN + "!");
                Bukkit.broadcastMessage(prefix + ChatColor.GREEN + "Buy your tickets with "+ ChatColor.BOLD + ChatColor.GOLD +"/lottery buy <amount>");
                Bukkit.broadcastMessage(footer);
                return true;

            } else if (args[0].equalsIgnoreCase("draw")) {

                Player player = (Player) sender;
                if (!player.hasPermission("lotteryplus.lottery.draw")) {
                    player.sendMessage("You do not have permission to execute this command.");
                    return true;
                }

                isDrawing = true;
                if (lotteryTicketHolders.size() > 0) {
                    Random random = new Random();
                    Player winner = lotteryTicketHolders.get(random.nextInt(lotteryTicketHolders.size()));
                    lastWinnerName = winner.getName();
                    lastWinnerAmount = lotteryPot;
                    balance.setBalance(winner, balance.getBalance(winner) + lotteryPot);
                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage(prefix + ChatColor.GREEN + "Congratulations! " + ChatColor.BOLD + ChatColor.GOLD + winner.getName() + ChatColor.RESET + ChatColor.GREEN + " has won the lottery and won " + ChatColor.BOLD + ChatColor.GOLD + formatCurrency(lotteryPot) + ChatColor.RESET + ChatColor.GREEN + "!");
                    Bukkit.broadcastMessage(footer);
                    lotteryTicketHolders.clear();
                    lotteryPot = 0.0;
                } else {
                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage(prefix + ChatColor.GREEN + "No one has bought lottery tickets, so no one won the lottery this round.");
                    Bukkit.broadcastMessage(footer);
                }
                isDrawing = false;

                if (lotteryDrawTaskId != -1) {
                    Bukkit.getScheduler().cancelTask(lotteryDrawTaskId);
                    scheduleLotteryDraw();
                    logger.info("[LotteryPlus] Lottery drawn early, restarting drawTask");
                }
                if (lotteryAnnounceTaskId != -1) {
                    Bukkit.getScheduler().cancelTask(lotteryAnnounceTaskId);
                    scheduleLotteryAnnounce();
                    logger.info("[LotteryPlus] Lottery drawn early, restarting announceTask");
                }

                return true;

            } else {
                sender.sendMessage(prefix + ChatColor.RED + "Invalid command. Usage: /lottery <buy | donate | chance>");
                return true;
            }
        }
        return false;
    }
}