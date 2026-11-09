package ir.layercloud.cortexdrone.client;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ir.layercloud.cortexdrone.service.question.QuestionLimits;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import java.util.List;

/**
 * The question dialog the drone opens when it needs a decision.
 *
 * <p>Options are buttons; free text is a field with Enter-to-send. Dismissing
 * with Esc (or closing the screen) reports a cancellation so the waiting tool
 * returns immediately instead of burning its full timeout. The screen does not
 * pause the game: the drone keeps working while the player reads.</p>
 */
@SideOnly(Side.CLIENT)
public final class QuestionScreen extends GuiScreen {
    private static final int PADDING = 12;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int PANEL_MAX_WIDTH = 320;
    private static final int SEND_ID = 100;
    private static final int CANCEL_ID = 101;

    private static final int TITLE_COLOR = 0xFF4FC3F7;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_COLOR = 0xFF9E9E9E;
    private static final int PANEL_COLOR = 0xD0101014;

    private final long requestId;
    private final String question;
    private final List<String> options;
    private final boolean allowFreeText;
    private final long closeAt;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private List<String> questionLines;
    private GuiTextField input;
    private GuiButton sendButton;
    private boolean finished;

    public QuestionScreen(long requestId, String question, List<String> options,
                          boolean allowFreeText, int timeoutMs) {
        this.requestId = requestId;
        this.question = question == null ? "" : question;
        this.options = options;
        this.allowFreeText = allowFreeText;
        this.closeAt = System.currentTimeMillis() + Math.max(1_000L, timeoutMs);
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.panelWidth = Math.min(PANEL_MAX_WIDTH, Math.max(160, this.width - 40));
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.questionLines = wrap(this.question, this.panelWidth - PADDING * 2);

        int contentHeight = 24 + this.questionLines.size() * 10 + 10;
        int optionsHeight = this.options.size() * (BUTTON_HEIGHT + GAP);
        int inputHeight = this.allowFreeText ? BUTTON_HEIGHT + GAP : 0;
        this.panelHeight = Math.min(this.height - 20,
                contentHeight + optionsHeight + inputHeight + BUTTON_HEIGHT + 26);
        this.panelTop = Math.max(10, (this.height - this.panelHeight) / 2);

        int left = this.panelLeft + PADDING;
        int buttonWidth = this.panelWidth - PADDING * 2;
        int cursor = this.panelTop + contentHeight;

        List<GuiButton> buttons = buttons(this.buttonList);
        for (int i = 0; i < this.options.size(); i++) {
            buttons.add(new GuiButton(i, left, cursor, buttonWidth, BUTTON_HEIGHT,
                    label(this.options.get(i), buttonWidth)));
            cursor += BUTTON_HEIGHT + GAP;
        }
        if (this.allowFreeText) {
            int sendWidth = 54;
            int fieldWidth = buttonWidth - sendWidth - GAP;
            this.input = new GuiTextField(this.fontRendererObj, left, cursor, fieldWidth,
                    BUTTON_HEIGHT);
            this.input.setMaxStringLength(QuestionLimits.MAX_ANSWER_CHARS);
            this.input.setFocused(true);
            this.sendButton = new GuiButton(SEND_ID, left + fieldWidth + GAP, cursor, sendWidth,
                    BUTTON_HEIGHT, "Send");
            this.sendButton.enabled = false;
            buttons.add(this.sendButton);
            cursor += BUTTON_HEIGHT + GAP;
        }
        int cancelWidth = Math.min(120, buttonWidth);
        buttons.add(new GuiButton(CANCEL_ID, this.panelLeft + (this.panelWidth - cancelWidth) / 2,
                cursor, cancelWidth, BUTTON_HEIGHT, "Dismiss"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(this.panelLeft, this.panelTop, this.panelLeft + this.panelWidth,
                this.panelTop + this.panelHeight, PANEL_COLOR);
        drawRect(this.panelLeft, this.panelTop, this.panelLeft + this.panelWidth,
                this.panelTop + 2, TITLE_COLOR);
        drawCenteredString(this.fontRendererObj, "CortexDrone asks", this.width / 2,
                this.panelTop + 8, TITLE_COLOR);

        int y = this.panelTop + 24;
        for (String line : this.questionLines) {
            drawString(this.fontRendererObj, line, this.panelLeft + PADDING, y, TEXT_COLOR);
            y += 10;
        }
        if (this.input != null) {
            this.input.drawTextBox();
            if (this.input.getText().isEmpty()) {
                drawString(this.fontRendererObj, "Type an answer...",
                        this.input.xPosition + 4, this.input.yPosition + 6, MUTED_COLOR);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawCenteredString(this.fontRendererObj, "Enter sends  -  Esc dismisses",
                this.width / 2, this.panelTop + this.panelHeight - 12, MUTED_COLOR);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == CANCEL_ID) {
            finish("", -1, true);
            return;
        }
        if (button.id == SEND_ID) {
            if (this.input != null && !this.input.getText().trim().isEmpty()) {
                finish(this.input.getText(), -1, false);
            }
            return;
        }
        if (button.id >= 0 && button.id < this.options.size()) {
            finish(this.options.get(button.id), button.id, false);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) {
            finish("", -1, true);
            return;
        }
        if (keyCode == 28 && this.input != null && !this.input.getText().trim().isEmpty()) {
            finish(this.input.getText(), -1, false);
            return;
        }
        if (this.input != null && this.input.textboxKeyTyped(typedChar, keyCode)) return;
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.input != null) this.input.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        if (this.input != null) {
            this.input.updateCursorCounter();
            if (this.sendButton != null) {
                this.sendButton.enabled = !this.input.getText().trim().isEmpty();
            }
        }
        if (!this.finished && System.currentTimeMillis() > this.closeAt) {
            // The server has already timed out; close without sending a stale answer.
            this.finished = true;
            this.mc.displayGuiScreen(null);
        }
    }

    @Override
    public void onGuiClosed() {
        if (this.finished) return;
        this.finished = true;
        QuestionClient.answer(this.requestId, "", -1, true);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void finish(String text, int optionIndex, boolean cancelled) {
        if (this.finished) return;
        this.finished = true;
        QuestionClient.answer(this.requestId, text, optionIndex, cancelled);
        this.mc.displayGuiScreen(null);
    }

    private String label(String text, int width) {
        String value = text == null ? "" : text;
        int max = Math.max(20, width - 12);
        String trimmed = this.fontRendererObj.trimStringToWidth(value, max);
        return trimmed.length() < value.length() ? trimmed + "..." : value;
    }

    /** 1.7.10 exposes the button list as a raw {@code List}. */
    @SuppressWarnings("unchecked")
    private static List<GuiButton> buttons(List<?> raw) {
        return (List<GuiButton>) raw;
    }

    /** 1.7.10 {@code listFormattedStringToWidth} returns a raw list of lines. */
    @SuppressWarnings("unchecked")
    private List<String> wrap(String text, int width) {
        return this.fontRendererObj.listFormattedStringToWidth(text, width);
    }
}
