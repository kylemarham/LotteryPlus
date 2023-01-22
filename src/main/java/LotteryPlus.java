import net.milkbowl.vault.economy.Economy;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
    private File dataFile;
    private FileConfiguration dataConfig;

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
        Interval = config.getInt("interval", 600);

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

        // Set the current time within data.yml to set the 'last draw' as when the plugin is enabled
        dataFile = new File(getDataFolder(), "data.yml");
        if(!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create data.yml file: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        dataConfig.set("last-draw-time", new Date().getTime());
        long nextDrawTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(60);
        dataConfig.set("nextDrawTime", nextDrawTime);
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save data.yml file: " + e.getMessage());
        }
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
                    for(Player player : Bukkit.getOnlinePlayers()) {
                        player.sendMessage(header);
                        player.sendMessage(colorize(prefix + "&aThe lottery will be drawn in " + getTimeUntilNextDraw()));
                        player.sendMessage(colorize(prefix + "&aThe current pot is &6&l" + formatCurrency(lotteryPot) + "&r&a with " + lotteryTicketHolders.size() + " tickets!"));
                        player.sendMessage(footer);
                    }
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
                    Bukkit.broadcastMessage( colorize(prefix + "&aCongratulations! &6&l" + winner.getName() + "&r&a has won the lottery and won &6&l" + formatCurrency(lotteryPot) + "&a!") );
                    Bukkit.broadcastMessage(footer);

                    lotteryTicketHolders.clear();
                    lotteryPot = 0.0;
                } else {
                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage( colorize(prefix + "&aNo one has bought lottery tickets, so no one won the lottery this round.") );
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

    private String getTimeUntilNextDraw() {
        long currentTime = System.currentTimeMillis();
        long nextDrawTime = dataConfig.getLong("nextDrawTime", 0);
        long timeUntilNextDraw = nextDrawTime - currentTime;

        // convert timeUntilNextDraw to minutes
        String minutesUntilNextDraw = TimeUnit.MILLISECONDS.toMinutes(timeUntilNextDraw) + " minutes";

        return minutesUntilNextDraw;
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

    public int getPlayerTicketCount(Player player) {
        int count = 0;
        for (Player p : lotteryTicketHolders) {
            if (p.getName().equals(player.getName())) {
                count++;
            }
        }
        return count;
    }

    public String colorize(String str){
        return str.replace('&', '§');
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("lottery")) {
            if (args.length == 0){
                sender.sendMessage( colorize("&d=== " +getDescription().getFullName()+ " ===") );
                sender.sendMessage( colorize("&aAuthor: OhSoGamer") );
                sender.sendMessage( colorize("&aVersion: " +  getDescription().getVersion() ) );
                sender.sendMessage( colorize("&aFeatures:") );
                sender.sendMessage( colorize("&a- Chance (Allows a user to chance some of their money)") );
                sender.sendMessage( colorize("&a- Donate (Allows a user to donate some of their money)") );
                sender.sendMessage( "" );
                sender.sendMessage( colorize("&aUse /lottery help for a full list of commands") );
                return true;
            }
            if (args[0].equalsIgnoreCase("help")) {
                sender.sendMessage( colorize("&d=== " +getDescription().getFullName()+ " ===") );
                sender.sendMessage( colorize("&aCommands:") );
                if (sender.hasPermission("lotteryplus.buy")) {
                    sender.sendMessage( colorize("&a- /lottery buy [amount]: &7Purchase lottery tickets") );
                }
                if (sender.hasPermission("lotteryplus.donate")) {
                    sender.sendMessage( colorize("&a- /lottery donate [amount]: &7Donate to the lottery pot") );
                }
                if (sender.hasPermission("lotteryplus.chance")) {
                    sender.sendMessage( colorize("&a- /lottery chance [amount]: &7Take a chance to win a return on your stake") );
                }
                if (sender.hasPermission("lotteryplus.status")) {
                    sender.sendMessage( colorize("&a- /lottery status: &7Check the current lottery status") );
                }
                if (sender.hasPermission("lotteryplus.announce")) {
                    sender.sendMessage( colorize("&a- /lottery announce: &7Check the current lottery status and when the next draw will be") );
                }
                if (sender.hasPermission("lotteryplus.boost")) {
                    sender.sendMessage( colorize("&a- /lottery boost: &7Boost the prize pool with a percentage of your balance") );
                }
                if (sender.hasPermission("lotteryplus.draw")) {
                    sender.sendMessage( colorize("&a- /lottery draw: &7Draw the lottery immediately") );
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("buy")) {

                if (!(sender instanceof Player)) {
                    sender.sendMessage("This command can only be executed by a player.");
                    return true;
                }

                Player player = (Player) sender;
                if (args.length != 2) {
                    player.sendMessage( colorize(prefix + "&cUsage: /lottery buy <number of tickets>") );
                    return true;
                }

                int ticketCount;
                try {
                    ticketCount = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage( colorize(prefix + "&cInvalid number of tickets.") );
                    return true;
                }
                if (ticketCount < 1 || ticketCount > maxTicketCount) {
                    player.sendMessage( colorize(prefix + "&cYou can only purchase between 1 and " + maxTicketCount + " tickets.") );
                    return true;
                }

                int currentTicketCount = getPlayerTicketCount(player);

                // If the user already has maximum tickets, prevent them from buying more
                if( currentTicketCount == maxTicketCount ){
                    player.sendMessage( colorize(prefix + "&cYou already have the maximum of " + maxTicketCount + " tickets.") );
                    return true;
                }

                // If the user tries to buy more than 100 tickets, limit them to 100-how many they are trying to buy
                if (currentTicketCount + ticketCount > maxTicketCount) {
                    ticketCount = maxTicketCount - currentTicketCount;
                    player.sendMessage( colorize(prefix + "&cYou can only purchase &6" + ticketCount + "&c more tickets") );
                }

                double totalPrice = ticketCount * ticketPrice;

                if (balance.getBalance(player) < totalPrice) {
                    player.sendMessage( colorize(prefix + "&cYou don't have enough money to purchase " + ticketCount + " tickets.") );
                    return true;
                }

                balance.setBalance(player, balance.getBalance(player) - totalPrice);
                lotteryPot += totalPrice;

                for (int i = 0; i < ticketCount; i++) {
                    lotteryTicketHolders.add(player);
                }

                player.sendMessage( colorize(prefix + "&aYou have successfully purchased &6" + ticketCount + "&a lottery tickets for &6" + formatCurrency(totalPrice)) );
                Bukkit.broadcastMessage(header);
                Bukkit.broadcastMessage( colorize(prefix + "&6" + player.getName() + "&a has purchased &6" + ticketCount + "&a /lottery tickets. There is now &6" + formatCurrency(lotteryPot) + "&a in the pot!") );
                Bukkit.broadcastMessage(footer);
                return true;
            } else if (args[0].equalsIgnoreCase("donate")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage( colorize(prefix + "&cThis command can only be executed by a player.") );
                    return true;
                }
                Player player = (Player) sender;
                if (args.length != 2) {
                    player.sendMessage( colorize(prefix + "&cUsage: /lottery donate <amount>") );
                    return true;
                }
                double donationAmount;
                try {
                    donationAmount = Double.parseDouble(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage( colorize(prefix + "&cInvalid donation amount.") );
                    return true;
                }
                if (donationAmount < 0) {
                    player.sendMessage( colorize(prefix + "&cDonation amount must be greater than or equal to 0.") );
                    return true;
                }
                if (balance.getBalance(player) < donationAmount) {
                    player.sendMessage( colorize(prefix + "&cYou don't have enough money to donate " + formatCurrency(donationAmount) + ".") );
                    return true;
                }
                balance.setBalance(player, balance.getBalance(player) - donationAmount);
                lotteryPot += donationAmount;
                player.sendMessage( colorize(prefix + "&aYou have successfully donated &6" + formatCurrency(donationAmount) + "&a to the lottery pot.") );
                Bukkit.broadcastMessage(header);
                Bukkit.broadcastMessage( colorize(prefix + "&6" + player.getName() + "&a has donated &6" + formatCurrency(donationAmount) + "&a to the lottery pot. There is now &6" + formatCurrency(lotteryPot) + "&a in the pot!") );
                Bukkit.broadcastMessage(footer);
                return true;
            } else if (args[0].equalsIgnoreCase("chance")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("This command can only be executed by a player.");
                    return true;
                }
                Player player = (Player) sender;

                if (args.length != 2) {
                    player.sendMessage( colorize(prefix + "&cUsage: /lottery chance <amount>") );
                    return true;
                }

                double stakeAmount;
                try {
                    stakeAmount = Double.parseDouble(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage( colorize(prefix + "&cInvalid stake amount.") );
                    return true;
                }

                if (stakeAmount <= 0) {
                    player.sendMessage( colorize(prefix + "&cStake amount must be greater than 0.") );
                    return true;
                }

                if (balance.getBalance(player) < stakeAmount) {
                    player.sendMessage( colorize(prefix + "&cYou don't have enough money to stake " + formatCurrency(stakeAmount) + ".") );
                    return true;
                }

                if (chanceCommandCooldown.containsKey(player) && System.currentTimeMillis() - chanceCommandCooldown.get(player) < 60000) {
                    player.sendMessage( colorize(prefix + "&cYou must wait before using the chance command again.") );
                    return true;
                } else {
                    chanceCommandCooldown.put(player, System.currentTimeMillis());
                }

                balance.setBalance(player, balance.getBalance(player) - stakeAmount);
                Random random = new Random();
                int chance = random.nextInt(100);

                if (chance <= chancePercent) {
                    double winnings = stakeAmount * chanceWin;
                    balance.setBalance(player, balance.getBalance(player) + winnings);
                    player.sendMessage( colorize(prefix + "&aCongratulations! You won &6" + formatCurrency(winnings) + "&a in the lottery chance game!") );

                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage( colorize(prefix + "&6" + player.getName() + "&a has chanced &6" + formatCurrency(stakeAmount) + "&a and won &6" + formatCurrency(winnings) + "&a! Take the chance yourself with /lottery chance <amount>!") );
                    Bukkit.broadcastMessage(footer);
                } else {
                    balance.setBalance(player, balance.getBalance(player) - stakeAmount);
                    lotteryPot += stakeAmount;
                    player.sendMessage( colorize(prefix + "&cSorry, you did not win this time. Better luck next time!") );

                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage( colorize(prefix + "&6" + player.getName() + "&a has risked &6" + formatCurrency(stakeAmount) + "&a and lost it all! The lottery amount is now &6" + formatCurrency(lotteryPot) + "&a!") );
                    Bukkit.broadcastMessage(footer);
                }

                return true;
            } else if (args[0].equalsIgnoreCase("announce")) {

                Player player = (Player) sender;
                if (!player.hasPermission("lotteryplus.announce")) {
                    player.sendMessage( colorize(prefix + "&cYou do not have permission to execute this command.") );
                    return true;
                }

                if (!(sender instanceof Player)) {
                    sender.sendMessage("This command can only be executed by a player.");
                    return true;
                }
                Bukkit.broadcastMessage(header);
                Bukkit.broadcastMessage( colorize(prefix + "&aThe lottery will be drawn in " + getTimeUntilNextDraw()) );
                Bukkit.broadcastMessage( colorize(prefix + "&aThe current pot is &6&l" + formatCurrency(lotteryPot) + "&r&a with " + lotteryTicketHolders.size() + " tickets!") );
                Bukkit.broadcastMessage(footer);
                return true;

            } else if (args[0].equalsIgnoreCase("status")) {

                Player player = (Player) sender;
                if (!player.hasPermission("lotteryplus.status")) {
                    player.sendMessage("&cYou do not have permission to execute this command.");
                    return true;
                }
                player.sendMessage(header);
                if( lastWinnerName != "" ){
                    Map<String,Object> data = getLastDrawData();
                    String lastWinner = (String) data.get("last-winner");
                    double lastPrize = (double) data.get("last-prize");

                    player.sendMessage( colorize(prefix + "&aLast lottery winner: " + lastWinner) );
                    player.sendMessage( colorize(prefix + "&aAmount won: " + formatCurrency(lastPrize)) );
                }
                player.sendMessage( colorize(prefix + "&aNumber of tickets in current lottery: " + lotteryTicketHolders.size()) );
                player.sendMessage( colorize(prefix + "&aCurrent prize pot: &6" + formatCurrency(lotteryPot)) );
                player.sendMessage( colorize(prefix + "&aThe next draw will be in " + getTimeUntilNextDraw()) );
                player.sendMessage(footer);
                return true;

            } else if (args[0].equalsIgnoreCase("boost")) {

                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    if (!player.hasPermission("lotteryplus.boost")) {
                        player.sendMessage("&cYou do not have permission to execute this command.");
                        return true;
                    }

                    double boostAmount;
                    try {
                        boostAmount = Double.parseDouble(args[1]);
                    } catch (NumberFormatException e) {
                        player.sendMessage( colorize(prefix + "&cInvalid boost amount.") );
                        return true;
                    }
                    if (boostAmount <= 0) {
                        player.sendMessage( colorize(prefix + "&cBoost amount must be greater than 0.") );
                        return true;
                    }
                    lotteryPot += boostAmount;

                    if (args.length == 2) {
                        Bukkit.broadcastMessage(header);
                        Bukkit.broadcastMessage( colorize(prefix + "&6" + player.getName() + "&a has boosted the lottery by &6&l" + formatCurrency(boostAmount) + "&r&a!") );
                        Bukkit.broadcastMessage( colorize(prefix + "&aBuy your tickets with &6&l" + "/lottery buy <amount>") );
                        Bukkit.broadcastMessage(footer);
                    }
                } else if (sender instanceof ConsoleCommandSender) {
                    ConsoleCommandSender console = (ConsoleCommandSender) sender;
                    double boostAmount;
                    boostAmount = Double.parseDouble(args[1]);
                    if (boostAmount <= 0) {
                        logger.info("Boost amount must be greater than 0.");
                        return true;
                    }
                    lotteryPot += boostAmount;

                    if (args.length == 2) {
                        Bukkit.broadcastMessage(header);
                        Bukkit.broadcastMessage( colorize(prefix + "&a The lottery pot has been boosted boosted by &6&l" + formatCurrency(boostAmount) + "&r&a!") );
                        Bukkit.broadcastMessage( colorize(prefix + "&aCurrent prize pot: &6" + formatCurrency(lotteryPot)) );
                        Bukkit.broadcastMessage( colorize(prefix + "&aBuy your tickets with &6&l" + "/lottery buy <amount>") );
                        Bukkit.broadcastMessage(footer);
                    }
                }
                return true;

            } else if (args[0].equalsIgnoreCase("draw")) {

                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    if (!player.hasPermission("lotteryplus.draw")) {
                        player.sendMessage("You do not have permission to execute this command.");
                        return true;
                    }
                }

                isDrawing = true;
                if (lotteryTicketHolders.size() > 0) {
                    Random random = new Random();
                    Player winner = lotteryTicketHolders.get(random.nextInt(lotteryTicketHolders.size()));
                    lastWinnerName = winner.getName();
                    lastWinnerAmount = lotteryPot;
                    balance.setBalance(winner, balance.getBalance(winner) + lotteryPot);

                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage( colorize(prefix + "&aCongratulations! &6&l" + winner.getName() + "&r&a has won the lottery and won &l&6" + formatCurrency(lotteryPot) + "&r&a!") );
                    Bukkit.broadcastMessage(footer);
                } else {
                    Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage( colorize(prefix + "&aNo one has bought lottery tickets, so no one won the lottery this round.") );
                    Bukkit.broadcastMessage(footer);
                }

                lotteryTicketHolders.clear();
                lotteryPot = 0.0;
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
                sender.sendMessage( colorize(prefix + "&aInvalid command. Usage: /lottery <buy | donate | chance>") );
                return true;
            }
        }
        return false;
    }
}