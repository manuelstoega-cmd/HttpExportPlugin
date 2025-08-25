package info.sigterm.plugins.httpserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;

import net.runelite.api.*;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.http.api.RuneLiteAPI;

@PluginDescriptor(
        name = "HTTP Full Export"
)
public class HttpServerPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    private HttpServer server;
    private int tickCounter = 0;

    @Override
    protected void startUp() throws Exception
    {
        server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/player", this::handlePlayer);
        server.createContext("/npcs", this::handleNpcs);
        server.createContext("/objects", this::handleObjects);
        server.createContext("/grounditems", this::handleGroundItems);
        server.createContext("/inventory", handlerForInv(InventoryID.INVENTORY));
        server.createContext("/equipment", handlerForInv(InventoryID.EQUIPMENT));
        server.createContext("/bank", this::handleBank);
        server.createContext("/stats", this::handleStats);
        server.createContext("/projectiles", this::handleProjectiles);
        server.createContext("/tick", this::handleTick);

        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
    }

    @Override
    protected void shutDown() throws Exception
    {
        server.stop(1);
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        tickCounter++;
    }

    // --- Handlers ---

    private void handlePlayer(HttpExchange exchange) throws IOException
    {
        JsonObject obj = new JsonObject();

        Player me = client.getLocalPlayer();
        if (me != null)
        {
            obj.addProperty("name", me.getName());
            obj.addProperty("x", me.getWorldLocation().getX());
            obj.addProperty("y", me.getWorldLocation().getY());
            obj.addProperty("plane", me.getWorldLocation().getPlane());
            obj.addProperty("animation", me.getAnimation());

            // HP & RunEnergy
            obj.addProperty("health", client.getBoostedSkillLevel(Skill.HITPOINTS));
            obj.addProperty("runEnergy", client.getEnergy());

            // Mausposition
            net.runelite.api.Point mouse = client.getMouseCanvasPosition();
            if (mouse != null)
            {
                JsonObject mouseObj = new JsonObject();
                mouseObj.addProperty("x", mouse.getX());
                mouseObj.addProperty("y", mouse.getY());
                obj.add("mouse", mouseObj);
            }
        }

        sendResponse(exchange, obj);
    }

    private void handleNpcs(HttpExchange exchange) throws IOException
    {
        JsonArray arr = new JsonArray();
        for (NPC npc : client.getNpcs())
        {
            if (npc == null || npc.getName() == null) continue;

            JsonObject o = new JsonObject();
            o.addProperty("id", npc.getId());
            o.addProperty("name", npc.getName());
            o.addProperty("x", npc.getWorldLocation().getX());
            o.addProperty("y", npc.getWorldLocation().getY());
            o.addProperty("plane", npc.getWorldLocation().getPlane());
            o.addProperty("healthRatio", npc.getHealthRatio());
            o.addProperty("animation", npc.getAnimation());
            arr.add(o);
        }
        sendResponse(exchange, arr);
    }

    private void handleObjects(HttpExchange exchange) throws IOException
    {
        JsonArray arr = new JsonArray();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();

        for (int z = 0; z < tiles.length; z++)
        {
            for (int x = 0; x < tiles[z].length; x++)
            {
                for (int y = 0; y < tiles[z][x].length; y++)
                {
                    Tile tile = tiles[z][x][y];
                    if (tile == null) continue;

                    for (GameObject obj : tile.getGameObjects())
                    {
                        if (obj == null) continue;

                        JsonObject o = new JsonObject();
                        o.addProperty("id", obj.getId());
                        o.addProperty("x", obj.getWorldLocation().getX());
                        o.addProperty("y", obj.getWorldLocation().getY());
                        o.addProperty("plane", obj.getWorldLocation().getPlane());
                        arr.add(o);
                    }
                }
            }
        }

        sendResponse(exchange, arr);
    }

    private void handleGroundItems(HttpExchange exchange) throws IOException
    {
        JsonArray arr = new JsonArray();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();

        for (int z = 0; z < tiles.length; z++)
        {
            for (int x = 0; x < tiles[z].length; x++)
            {
                for (int y = 0; y < tiles[z][x].length; y++)
                {
                    Tile tile = tiles[z][x][y];
                    if (tile == null || tile.getGroundItems() == null) continue;

                    for (TileItem item : tile.getGroundItems())
                    {
                        JsonObject o = new JsonObject();
                        o.addProperty("id", item.getId());
                        o.addProperty("quantity", item.getQuantity());
                        o.addProperty("x", tile.getWorldLocation().getX());
                        o.addProperty("y", tile.getWorldLocation().getY());
                        arr.add(o);
                    }
                }
            }
        }

        sendResponse(exchange, arr);
    }

    private void handleBank(HttpExchange exchange) throws IOException
    {
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        sendItems(exchange, bank);
    }

    private void handleStats(HttpExchange exchange) throws IOException
    {
        JsonArray skills = new JsonArray();
        for (Skill skill : Skill.values())
        {
            if (skill == Skill.OVERALL) continue;

            JsonObject object = new JsonObject();
            object.addProperty("stat", skill.getName());
            object.addProperty("level", client.getRealSkillLevel(skill));
            object.addProperty("boostedLevel", client.getBoostedSkillLevel(skill));
            object.addProperty("xp", client.getSkillExperience(skill));
            skills.add(object);
        }
        sendResponse(exchange, skills);
    }

    private void handleProjectiles(HttpExchange exchange) throws IOException
    {
        JsonArray arr = new JsonArray();
        for (Projectile p : client.getProjectiles())
        {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.getId());
            o.addProperty("remainingCycles", p.getRemainingCycles());
            o.addProperty("startX", p.getX1());
            o.addProperty("startY", p.getY1());
            o.addProperty("currentX", p.getX());
            o.addProperty("currentY", p.getY());
            o.addProperty("z", p.getZ());
            arr.add(o);
        }
        sendResponse(exchange, arr);
    }

    private void handleTick(HttpExchange exchange) throws IOException
    {
        JsonObject obj = new JsonObject();
        obj.addProperty("tick", tickCounter);
        sendResponse(exchange, obj);
    }

    // --- Helpers ---

    private HttpHandler handlerForInv(InventoryID id)
    {
        return exchange -> {
            ItemContainer container = client.getItemContainer(id);
            sendItems(exchange, container);
        };
    }

    private void sendItems(HttpExchange exchange, ItemContainer container) throws IOException
    {
        if (container == null)
        {
            exchange.sendResponseHeaders(204, 0);
            return;
        }

        Item[] items = container.getItems();
        sendResponse(exchange, items);
    }

    private void sendResponse(HttpExchange exchange, Object data) throws IOException
    {
        exchange.sendResponseHeaders(200, 0);
        try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
        {
            RuneLiteAPI.GSON.toJson(data, out);
        }
    }

    private <T> T invokeAndWait(Callable<T> r)
    {
        try
        {
            AtomicReference<T> ref = new AtomicReference<>();
            Semaphore semaphore = new Semaphore(0);
            clientThread.invokeLater(() -> {
                try { ref.set(r.call()); }
                catch (Exception e) { throw new RuntimeException(e); }
                finally { semaphore.release(); }
            });
            semaphore.acquire();
            return ref.get();
        }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
