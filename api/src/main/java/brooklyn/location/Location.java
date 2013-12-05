package brooklyn.location;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;

import brooklyn.config.ConfigKey;
import brooklyn.config.ConfigKey.HasConfigKey;
import brooklyn.entity.rebind.RebindSupport;
import brooklyn.entity.rebind.Rebindable;
import brooklyn.entity.trait.Identifiable;
import brooklyn.mementos.LocationMemento;

/**
 * A location that an entity can be in. Examples of locations include a single machine
 * or a pool of machines, or a region within a given cloud. 
 * 
 * See {@link brooklyn.entity.trait.Startable#start(Collection)}.
 * 
 * Locations may not be {@link Serializable} in subsequent releases!
 */
public interface Location extends Serializable, Identifiable, Rebindable {

    /**
     * A unique id for this location.
     */
    @Override
    String getId();

    /**
     * Get the name assigned to this location.
     *
     * @return the name assigned to the location.
     * @since 0.6 (previously getName())
     */
    String getDisplayName();

    /**
     * Get the 'parent' of this location. Locations are organized into a tree hierarchy, and this method will return a reference
     * to the parent of this location, or {@code null} if this location is the tree root.
     *
     * @return a reference to the parent of this location, or {@code null} if this location is the tree root.
     * @since 0.6 (previously getParentLocation())
     */
    Location getParent();

    /**
     * Get the 'children' of this location. Locations are organized into a tree hierarchy, and this method will return a
     * collection containing the children of this location. This collection is an unmodifiable view of the data.
     *
     * @return a collection containing the children of this location.
     * @since 0.6 (previously getChildLocations())
     */
    Collection<Location> getChildren();

    /**
     * @deprecated since 0.6
     * @see #getDisplayName()
     */
    @Deprecated
    String getName();

    /**
     * @deprecated since 0.6
     * @see #getParent()
     */
    @Deprecated
    Location getParentLocation();

    /**
     * Set the 'parent' of this location. If this location was previously a child of a different location, it is removed from
     * the other location first. It is valid to pass in {@code null} to indicate that the location should be disconnected
     * from its parent.
     * 
     * Adds this location as a child of the new parent (see {@code getChildLocations()}).
     *
     * @param newParent the new parent location object, or {@code null} to clear the parent reference.
     * @since 0.6 (previously setParentLocation(Location))
     */
    void setParent(Location newParent);

    /**
     * @deprecated since 0.6
     * @see #setParent(Location)
     */
    @Deprecated
    void setParentLocation(Location newParent);


    /**
     * @deprecated since 0.6
     * @see #getChildren()
     */
    @Deprecated
    Collection<Location> getChildLocations();

    /**
     * @return meta-data about the location (usually a long line, or a small number of lines).
     * 
     * @since 0.6
     */
    String toVerboseString();
    
    /**
     * Answers true if this location equals or is an ancestor of the given location.
     */
    boolean containsLocation(Location potentialDescendent);

    /** Returns configuration set at this location or inherited or default */
    <T> T getConfig(ConfigKey<T> key);
    
    <T> T getConfig(HasConfigKey<T> key);

    /** True iff the indication config key is set _at_ this location (not parents) 
     * @deprecated since 0.6.0 use {@link #hasConfig(ConfigKey, boolean)} */
    @Deprecated
    boolean hasConfig(ConfigKey<?> key);

    /** Returns all config set _at_ this location (not inherited)
     * @deprecated since 0.6.0 use {@link #getAllConfig(boolean) */
    @Deprecated
    Map<String,Object> getAllConfig();

    /** True iff the indication config key is set, either inherited (second argument true) or locally-only (second argument false) */
    boolean hasConfig(ConfigKey<?> key, boolean includeInherited);

    /** Returns all config set, either inherited (argument true) or locally-only (argument false) */
    public Map<String,Object> getAllConfig(boolean includeInherited);
    
    @Override
    RebindSupport<LocationMemento> getRebindSupport();

    /**
     * Whether this location has support for the given extension type.
     * See additional comments in {@link #getExtension(Class)}.
     * 
     * @throws NullPointerException if extensionType is null
     */
    boolean hasExtension(Class<?> extensionType);

    /**
     * Returns an extension of the given type. Note that the type must be an exact match for
     * how the extension was registered (e.g. {@code getExtension(Object.class)} will not match
     * anything, even though registered extension extend {@link Object}.
     * <p>
     * This will not look at extensions of {@link #getParent()}.
     * 
     * @throws IllegalArgumentException if this location does not support the given extension type
     * @throws NullPointerException if extensionType is null
     */
    <T> T getExtension(Class<T> extensionType);
}