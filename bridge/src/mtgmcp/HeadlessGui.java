package mtgmcp;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.BuildInfo;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import org.jupnp.DefaultUpnpServiceConfiguration;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** GuiBase for a JVM with no display. One daemon thread stands in for the Swing EDT. */
final class HeadlessGui implements IGuiBase {
    private volatile Thread guiThread;
    private final ExecutorService edt = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "headless-edt");
        t.setDaemon(true);
        guiThread = t;
        return t;
    });

    @Override public boolean isRunningOnDesktop() { return true; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return BuildInfo.getVersionString(); }

    @Override public void invokeInEdtNow(Runnable r) { if (isGuiThread()) r.run(); else invokeInEdtAndWait(r); }
    @Override public void invokeInEdtLater(Runnable r) { edt.execute(r); }
    @Override public void invokeInEdtAndWait(Runnable r) {
        if (isGuiThread()) { r.run(); return; }
        try {
            edt.submit(r).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }
    @Override public void runBackgroundTask(String message, Runnable task) { new Thread(task, "bg-" + message).start(); }
    @Override public boolean isGuiThread() { return Thread.currentThread() == guiThread; }

    @Override public String getAssetsDir() { return ""; }
    @Override public ImageFetcher getImageFetcher() { return null; }
    @Override public ISkinImage getSkinIcon(FSkinProp skinProp) { return null; }
    @Override public ISkinImage getUnskinnedIcon(String path) { return null; }
    @Override public ISkinImage getCardArt(PaperCard card, boolean backFace) { return null; }
    @Override public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) { return null; }
    @Override public void clearImageCache() { }
    @Override public String encodeSymbols(String str, boolean formatReminderText) { return str; }
    @Override public int getAvatarCount() { return 0; }
    @Override public int getSleevesCount() { return 0; }
    @Override public float getScreenScale() { return 1f; }
    @Override public void preventSystemSleep(boolean preventSleep) { }
    @Override public void download(GuiDownloadService service, Consumer<Boolean> callback) { if (callback != null) callback.accept(false); }
    @Override public void copyToClipboard(String text) { }
    @Override public void browseToUrl(String url) { }

    @Override public void showCardList(String title, String message, List<PaperCard> list) { }
    @Override public boolean showBoxedProduct(String title, String message, List<PaperCard> list) { return false; }
    @Override public void showBugReportDialog(String title, String text, boolean showExitAppBtn) { Log.info("bug report: " + title + "\n" + text); }
    @Override public void showImageDialog(ISkinImage image, String message, String title) { }
    @Override public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        Log.info("option dialog '" + title + "': " + message + " -> default " + defaultOption);
        return defaultOption;
    }
    @Override public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        return initialInput != null ? initialInput : "";
    }
    @Override public String showFileDialog(String title, String defaultDir) { return null; }
    @Override public File getSaveFile(File defaultFile) { return defaultFile; }
    @Override public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices) { return sourceChoices; }
    @Override public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) {
        return new ArrayList<>(choices).subList(0, Math.min(Math.max(min, 0), choices.size()));
    }
    @Override public PaperCard chooseCard(String title, String message, List<PaperCard> list) { return list.isEmpty() ? null : list.get(0); }

    @Override public boolean isSupportedAudioFormat(File file) { return false; }
    @Override public IAudioClip createAudioClip(String filename) { return null; }
    @Override public IAudioMusic createAudioMusic(String filename) { return null; }
    @Override public void startAltSoundSystem(String filename, boolean isSynchronized) { }
    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }

    @Override public IGuiGame getNewGuiGame() { throw new UnsupportedOperationException("no local human players"); }
    @Override public HostedMatch hostMatch() { return new HostedMatch(); }
    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return new DefaultUpnpServiceConfiguration(); }
    @Override public boolean hasNetGame() { return true; }
}
