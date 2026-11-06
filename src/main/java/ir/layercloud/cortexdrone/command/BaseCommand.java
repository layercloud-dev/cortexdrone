package ir.layercloud.cortexdrone.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;

/**
 * Base for project commands. Concrete commands live under {@code command.impl}.
 */
public abstract class BaseCommand extends CommandBase {
    @Override
    public int compareTo(Object other) {
        if (other instanceof ICommand) {
            return this.getCommandName().compareTo(((ICommand) other).getCommandName());
        }
        return 0;
    }
}
