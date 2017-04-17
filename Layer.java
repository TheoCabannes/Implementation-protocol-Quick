/**
 * This interface declares the basic functions of any layer.
 * 
 */
public interface Layer {

  /**
   * Sends a payload downwards through this layer.
   * 
   * @param payload
   *          the data to be sent
   */
  public void send(byte[] payload);

  /**
   * Handles an incoming payload at this layer. This method is invoked from the
   * layer below, to pass it any payload which must upwards. This method should
   * not block and must return as soon as possible.
   * 
   * @param payload
   *          the data that will be handled by this layer
   * @param source
   *          a String identifying the source
   */
  public void receive(byte[] payload, String source);

  /**
   * Specifies to this layer another layer, to which payloads must be passed
   * upwards. Then, this layer will invoke the {@link #receive receive} method
   * of the specified {@code above} layer.
   * 
   * @param above
   *          the {@code Layer} whose {@link #receive receive} method will be
   *          called for passing data upwards
   */
  public void deliverTo(Layer above);

  /**
   * Closes this layer. This method should make the best effort to release
   * ressources.
   */
  public void close();
}
