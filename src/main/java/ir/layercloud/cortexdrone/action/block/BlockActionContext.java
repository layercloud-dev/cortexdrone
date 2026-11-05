package ir.layercloud.cortexdrone.action.block;

import ir.layercloud.cortexdrone.action.ActionContextBase;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Lifecycle for one break/place primitive. A break is retained until the
 * controller stops mining that exact block; a place finishes inside the RPC
 * call and is discarded immediately.
 */
public final class BlockActionContext extends ActionContextBase {
    private int[] target;
    private boolean breakOperation;
    private int ticks;

    public BlockActionContext(DroneController controller, EntityPlayerMP player, String operation) {
        super(controller, player, operation);
    }

    public void trackBreak(int x, int y, int z) {
        this.target = new int[]{x, y, z};
        this.breakOperation = true;
        retain();
    }

    @Override
    protected void onTick() {
        DroneController ctrl = controller();
        if (!this.breakOperation || ctrl == null || ctrl.drone == null
            || ctrl.drone.field_70128_L || ctrl.mode != Mode.MINE
            || ctrl.currentMineTarget == null || !matches(ctrl.currentMineTarget)) {
            complete();
            return;
        }
        this.ticks++;
    }

    private boolean matches(int[] current) {
        return this.target != null && current != null
            && current[0] == this.target[0]
            && current[1] == this.target[1]
            && current[2] == this.target[2];
    }

    public int[] target() {
        return this.target != null ? new int[]{this.target[0], this.target[1], this.target[2]} : null;
    }

    public int ticks() { return this.ticks; }
}
